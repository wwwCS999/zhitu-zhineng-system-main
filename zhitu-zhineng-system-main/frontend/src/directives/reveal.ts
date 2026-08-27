import type { Directive } from 'vue'

const observer = typeof window !== 'undefined'
  ? new IntersectionObserver(
      entries => {
        entries.forEach(entry => {
          if (entry.isIntersecting) {
            entry.target.classList.add('is-visible')
            observer?.unobserve(entry.target)
          }
        })
      },
      { threshold: 0.08, rootMargin: '0px 0px -24px' }
    )
  : null

export const reveal: Directive<HTMLElement, number | undefined> = {
  mounted(el, binding) {
    el.classList.add('reveal-item')
    if (typeof binding.value === 'number') {
      el.style.setProperty('--reveal-delay', `${binding.value}ms`)
    }
    observer?.observe(el)
  },
  unmounted(el) {
    observer?.unobserve(el)
  }
}
