import '@testing-library/jest-dom/vitest'

// antd 在 jsdom 下依赖 matchMedia / getComputedStyle 等浏览器 API，补桩避免报错。
if (typeof window !== 'undefined' && !window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as unknown as MediaQueryList
}

// antd 的 wave / 水波纹会用 getComputedStyle 校验 transform，缺省返回空对象
const origGetComputedStyle = window.getComputedStyle
window.getComputedStyle = ((el: Element) => origGetComputedStyle(el)) as typeof window.getComputedStyle