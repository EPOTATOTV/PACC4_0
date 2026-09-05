import { GlobalOutlined } from '@ant-design/icons'
import { Dropdown } from 'antd'
import { useI18n } from '../i18n'

/**
 * 前台语言切换器：下拉选择简体中文 / 繁体中文 / English，并同步 antd 语言。
 */
export default function LanguageSwitcher({ compact }: { compact?: boolean }) {
  const { locale, setLocale, locales } = useI18n()
  const current = locales.find((l) => l.code === locale) ?? locales[0]

  return (
    <Dropdown
      menu={{
        items: locales.map((l) => ({
          key: l.code,
          label: l.label,
          selected: l.code === locale,
        })),
        onClick: ({ key }) => setLocale(key as typeof locale),
        selectable: true,
      }}
      trigger={['click']}
    >
      <span
        style={{
          cursor: 'pointer',
          color: '#c9d1d9',
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
          padding: '0 4px',
          fontSize: compact ? 12 : 13,
        }}
      >
        <GlobalOutlined />
        {compact ? '' : current.label}
      </span>
    </Dropdown>
  )
}