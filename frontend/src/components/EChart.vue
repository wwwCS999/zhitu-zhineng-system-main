<script setup lang="ts">
import * as echarts from 'echarts'
import {
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch
} from 'vue'

const props = withDefaults(
  defineProps<{
    option: any
    height?: string
    renderer?: 'canvas' | 'svg'
    loading?: boolean
  }>(),
  {
    height: '320px',
    renderer: 'canvas',
    loading: false
  }
)

const emit = defineEmits<{
  click: [params: any]
  dblclick: [params: any]
  mouseover: [params: any]
  mouseout: [params: any]
}>()

const root = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null

function bindEvents() {
  if (!chart) {
    return
  }

  chart.on('click', params => emit('click', params))
  chart.on('dblclick', params => emit('dblclick', params))
  chart.on('mouseover', params => emit('mouseover', params))
  chart.on('mouseout', params => emit('mouseout', params))
}

function createChart() {
  if (!root.value) {
    return
  }

  chart?.dispose()

  chart = echarts.init(root.value, undefined, {
    renderer: props.renderer
  })

  bindEvents()

  chart.setOption(props.option || {}, {
    notMerge: true,
    lazyUpdate: false
  })

  updateLoading()
}

function updateOption(value: any) {
  if (!chart) {
    return
  }

  chart.setOption(value || {}, {
    notMerge: false,
    lazyUpdate: true
  })
}

function updateLoading() {
  if (!chart) {
    return
  }

  if (props.loading) {
    chart.showLoading('default', {
      text: '正在构建能力图谱…',
      color: '#51999F',
      textColor: '#55736D',
      maskColor: 'rgba(248, 251, 248, 0.76)'
    })
  } else {
    chart.hideLoading()
  }
}

function getInstance() {
  return chart
}

function resize() {
  chart?.resize()
}

function restoreView() {
  if (!chart) {
    return
  }

  chart.clear()
  chart.setOption(props.option || {}, {
    notMerge: true,
    lazyUpdate: false
  })

  nextTick(() => chart?.resize())
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')

  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)

  URL.revokeObjectURL(url)
}

function getSvgElement(): SVGSVGElement {
  if (!root.value) {
    throw new Error('图谱容器尚未初始化')
  }

  const svg = root.value.querySelector('svg')

  if (!(svg instanceof SVGSVGElement)) {
    throw new Error('当前图谱不是 SVG 渲染模式')
  }

  return svg
}

function serializeSvg(): string {
  const svg = getSvgElement().cloneNode(true) as SVGSVGElement

  if (!svg.getAttribute('xmlns')) {
    svg.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  }

  const width = chart?.getWidth() || root.value?.clientWidth || 1200
  const height = chart?.getHeight() || root.value?.clientHeight || 740

  svg.setAttribute('width', String(width))
  svg.setAttribute('height', String(height))

  return new XMLSerializer().serializeToString(svg)
}

function exportSvg(filename = '岗位能力图谱.svg') {
  const content = serializeSvg()
  const blob = new Blob([content], {
    type: 'image/svg+xml;charset=utf-8'
  })

  downloadBlob(blob, filename)
}

async function exportPng(
  filename = '岗位能力图谱.png',
  pixelRatio = 2
) {
  if (!chart) {
    throw new Error('图谱尚未初始化')
  }

  if (props.renderer === 'canvas') {
    const dataUrl = chart.getDataURL({
      type: 'png',
      pixelRatio,
      backgroundColor: '#F8FBF8'
    })

    const anchor = document.createElement('a')
    anchor.href = dataUrl
    anchor.download = filename
    document.body.appendChild(anchor)
    anchor.click()
    document.body.removeChild(anchor)
    return
  }

  const svgText = serializeSvg()
  const svgBlob = new Blob([svgText], {
    type: 'image/svg+xml;charset=utf-8'
  })
  const svgUrl = URL.createObjectURL(svgBlob)

  try {
    const image = new Image()

    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve()
      image.onerror = () => reject(new Error('SVG 转 PNG 失败'))
      image.src = svgUrl
    })

    const width = Math.max(1, chart.getWidth())
    const height = Math.max(1, chart.getHeight())
    const canvas = document.createElement('canvas')

    canvas.width = Math.round(width * pixelRatio)
    canvas.height = Math.round(height * pixelRatio)

    const context = canvas.getContext('2d')

    if (!context) {
      throw new Error('浏览器无法创建 Canvas')
    }

    context.fillStyle = '#F8FBF8'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.drawImage(image, 0, 0, canvas.width, canvas.height)

    const pngUrl = canvas.toDataURL('image/png')
    const anchor = document.createElement('a')

    anchor.href = pngUrl
    anchor.download = filename
    document.body.appendChild(anchor)
    anchor.click()
    document.body.removeChild(anchor)
  } finally {
    URL.revokeObjectURL(svgUrl)
  }
}

watch(
  () => props.option,
  value => updateOption(value),
  { deep: true }
)

watch(
  () => props.loading,
  () => updateLoading()
)

watch(
  () => props.renderer,
  async () => {
    await nextTick()
    createChart()
  }
)

onMounted(async () => {
  await nextTick()
  createChart()

  if (root.value) {
    observer = new ResizeObserver(() => chart?.resize())
    observer.observe(root.value)
  }

  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null

  window.removeEventListener('resize', resize)

  chart?.dispose()
  chart = null
})

defineExpose({
  getInstance,
  resize,
  restoreView,
  exportSvg,
  exportPng
})
</script>

<template>
  <div
    ref="root"
    class="echart-root"
    :style="{ height }"
  />
</template>

<style scoped>
.echart-root {
  width: 100%;
  min-width: 0;
}
</style>
