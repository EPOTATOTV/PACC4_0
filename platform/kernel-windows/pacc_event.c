// pacc_event.c - 有界事件环形缓冲 + 内核侧字符串工具
// 设计：非分页池一次性预分配 Capacity 条目，绝不在运行时增长。
//       写满时丢弃最旧（Head 前移）并累加 Dropped —— 被洪泛也无法耗尽池。
// 并发：所有读写均在 KSPIN_LOCK 下进行，可在 DISPATCH_LEVEL 以上的回调中调用。
#include "pacc_common.h"
#include <ntstrsafe.h>

typedef struct _PACC_EVENT_RING {
    KSPIN_LOCK     Lock;
    ULONG          Capacity;
    ULONG          Count;
    ULONG          Head;          // 最旧条目下标
    PPACC_EVENT    Entries;       // 非分页池，Capacity 个
    volatile LONG64 Pushed;
    volatile LONG64 Dropped;
    volatile LONG64 ClassCount[PACC_EVENT_CLASS_MAX + 1];  // 下标 1..4
    volatile LONG64 Suspicious[PACC_EVENT_CLASS_MAX + 1];  // 各监视器可疑命中
} PACC_EVENT_RING;

static PACC_EVENT_RING g_paccEventRing;

// ---------------------------------------------------------------------------
// 内核侧字符串工具
// ---------------------------------------------------------------------------
static WCHAR
PaccUpcaseChar(WCHAR c)
{
    if (c >= L'a' && c <= L'z') return (WCHAR)(c - (L'a' - L'A'));
    return c;
}

// 不区分大小写的整串比较（要求两者均以 NUL 结尾）。
BOOLEAN
PaccStrEqualCI(PCWSTR a, PCWSTR b)
{
    if (a == NULL || b == NULL) return FALSE;
    while (*a != L'\0' && *b != L'\0') {
        if (PaccUpcaseChar(*a) != PaccUpcaseChar(*b)) return FALSE;
        a++; b++;
    }
    return (*a == L'\0' && *b == L'\0');
}

// 不区分大小写的子串包含判定。
BOOLEAN
PaccStrContainsCI(PCWSTR haystack, PCWSTR needle)
{
    if (haystack == NULL || needle == NULL) return FALSE;
    if (*needle == L'\0') return TRUE;
    for (; *haystack != L'\0'; haystack++) {
        PCWSTR h = haystack;
        PCWSTR n = needle;
        while (*h != L'\0' && *n != L'\0' && PaccUpcaseChar(*h) == PaccUpcaseChar(*n)) {
            h++; n++;
        }
        if (*n == L'\0') return TRUE;
    }
    return FALSE;
}

// 将 UNICODE_STRING 拷贝进定长宽字符缓冲（截断并保证 NUL 结尾）。
VOID
PaccCopyUnicode(PCUNICODE_STRING src, PWCHAR dst, ULONG cchDst)
{
    if (dst == NULL || cchDst == 0) return;
    dst[0] = L'\0';
    if (src == NULL || src->Buffer == NULL || src->Length == 0) return;

    ULONG chars = src->Length / sizeof(WCHAR);
    if (chars >= cchDst) chars = cchDst - 1;
    __try {
        RtlCopyMemory(dst, src->Buffer, (SIZE_T)chars * sizeof(WCHAR));
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        dst[0] = L'\0';
        return;
    }
    dst[chars] = L'\0';
}

// ---------------------------------------------------------------------------
// 环形缓冲
// ---------------------------------------------------------------------------
NTSTATUS
PaccEventInitialize(VOID)
{
    RtlZeroMemory(&g_paccEventRing, sizeof(g_paccEventRing));
    KeInitializeSpinLock(&g_paccEventRing.Lock);
    g_paccEventRing.Capacity = PACC_EVENT_RING_CAPACITY;

    g_paccEventRing.Entries = (PPACC_EVENT)ExAllocatePool2(
        POOL_FLAG_NON_PAGED,
        (SIZE_T)PACC_EVENT_RING_CAPACITY * sizeof(PACC_EVENT),
        'ccap');
    if (g_paccEventRing.Entries == NULL) {
        // 失败即上报，绝不带着空缓冲继续运行。
        g_paccEventRing.Capacity = 0;
        DbgPrint("[PACC] event ring allocation failed\n");
        return STATUS_INSUFFICIENT_RESOURCES;
    }
    RtlZeroMemory(g_paccEventRing.Entries,
                  (SIZE_T)PACC_EVENT_RING_CAPACITY * sizeof(PACC_EVENT));
    return STATUS_SUCCESS;
}

VOID
PaccEventUninitialize(VOID)
{
    if (g_paccEventRing.Entries != NULL) {
        ExFreePoolWithTag(g_paccEventRing.Entries, 'ccap');
        g_paccEventRing.Entries = NULL;
    }
    g_paccEventRing.Capacity = 0;
    g_paccEventRing.Count = 0;
    g_paccEventRing.Head = 0;
}

// 入队一条事件。任意 IRQL ≤ DISPATCH_LEVEL 可调用；不分配、不阻塞。
VOID
PaccEventPush(ULONG eventClass, ULONG flags, ULONG pid, ULONG parentPid,
              ULONG64 aux1, ULONG64 aux2, PCWSTR detail)
{
    if (g_paccEventRing.Entries == NULL || g_paccEventRing.Capacity == 0) return;
    if (eventClass == 0 || eventClass > PACC_EVENT_CLASS_MAX) return;

    KIRQL oldIrql;
    KeAcquireSpinLock(&g_paccEventRing.Lock, &oldIrql);

    ULONG capacity = g_paccEventRing.Capacity;
    if (g_paccEventRing.Count == capacity) {
        // 满：丢弃最旧条目并计数，容量恒定。
        g_paccEventRing.Head = (g_paccEventRing.Head + 1) % capacity;
        g_paccEventRing.Count--;
        InterlockedIncrement64(&g_paccEventRing.Dropped);
    }

    ULONG index = (g_paccEventRing.Head + g_paccEventRing.Count) % capacity;
    PPACC_EVENT e = &g_paccEventRing.Entries[index];
    e->Class     = eventClass;
    e->Flags     = flags;
    e->Pid       = pid;
    e->ParentPid = parentPid;
    e->TimeStamp = (ULONG64)KeQueryInterruptTime();
    e->Aux1      = aux1;
    e->Aux2      = aux2;
    RtlStringCchCopyW(e->Detail, PACC_EVENT_DETAIL_MAX, (detail != NULL) ? detail : L"");

    g_paccEventRing.Count++;
    InterlockedIncrement64(&g_paccEventRing.Pushed);
    InterlockedIncrement64(&g_paccEventRing.ClassCount[eventClass]);

    KeReleaseSpinLock(&g_paccEventRing.Lock, oldIrql);
}

// 排空指定类别（0 = 全部）的事件到调用方缓冲。返回实际写出条数。
// 未命中的条目按原相对顺序原地压紧保留，被取出的条目从环中移除。
ULONG
PaccEventDrain(ULONG eventClass, PPACC_EVENT out, ULONG maxCount)
{
    if (g_paccEventRing.Entries == NULL || out == NULL || maxCount == 0) return 0;

    KIRQL oldIrql;
    KeAcquireSpinLock(&g_paccEventRing.Lock, &oldIrql);

    ULONG capacity = g_paccEventRing.Capacity;
    ULONG written = 0;
    ULONG kept = 0;

    for (ULONG i = 0; i < g_paccEventRing.Count; i++) {
        ULONG src = (g_paccEventRing.Head + i) % capacity;
        PPACC_EVENT e = &g_paccEventRing.Entries[src];
        BOOLEAN match = (eventClass == PACC_EVENT_CLASS_ALL) || (e->Class == eventClass);
        if (match && written < maxCount) {
            out[written] = *e;      // 拷贝出环
            written++;
        } else {
            ULONG dst = (g_paccEventRing.Head + kept) % capacity;  // dst 始终落后于 src
            if (dst != src) g_paccEventRing.Entries[dst] = *e;
            kept++;
        }
    }
    g_paccEventRing.Count = kept;

    KeReleaseSpinLock(&g_paccEventRing.Lock, oldIrql);
    return written;
}

VOID
PaccEventResetCounters(VOID)
{
    InterlockedExchange64(&g_paccEventRing.Pushed, 0);
    InterlockedExchange64(&g_paccEventRing.Dropped, 0);
    for (ULONG i = 0; i <= PACC_EVENT_CLASS_MAX; i++) {
        InterlockedExchange64(&g_paccEventRing.ClassCount[i], 0);
        InterlockedExchange64(&g_paccEventRing.Suspicious[i], 0);
    }
}

VOID
PaccEventGetStats(PPACC_EVENT_STATS out)
{
    if (out == NULL) return;
    RtlZeroMemory(out, sizeof(*out));
    out->Pushed   = (ULONG64)InterlockedCompareExchange64(&g_paccEventRing.Pushed, 0, 0);
    out->Dropped  = (ULONG64)InterlockedCompareExchange64(&g_paccEventRing.Dropped, 0, 0);
    out->Capacity = (ULONG64)g_paccEventRing.Capacity;
    for (ULONG i = 0; i <= PACC_EVENT_CLASS_MAX; i++) {
        out->ClassCount[i] = (ULONG64)InterlockedCompareExchange64(&g_paccEventRing.ClassCount[i], 0, 0);
        out->Suspicious[i] = (ULONG64)InterlockedCompareExchange64(&g_paccEventRing.Suspicious[i], 0, 0);
    }
}

VOID
PaccMonitorHitInc(ULONG eventClass)
{
    if (eventClass == 0 || eventClass > PACC_EVENT_CLASS_MAX) return;
    InterlockedIncrement64(&g_paccEventRing.Suspicious[eventClass]);
}