import type { ReactNode } from 'react'

// A 240° arc, open at the bottom: filled neon stroke for the value, a dotted
// inner guide, and hatched ticks over the unfilled remainder.
const CX = 150
const CY = 140
const R = 104
const START = 210 // degrees, measured counter-clockwise from 3 o'clock
const SWEEP = 240

function point(deg: number, r: number) {
  const rad = (deg * Math.PI) / 180
  return { x: CX + r * Math.cos(rad), y: CY - r * Math.sin(rad) }
}

function arc(from: number, to: number, r: number) {
  const a = point(from, r)
  const b = point(to, r)
  const large = from - to > 180 ? 1 : 0
  return `M ${a.x} ${a.y} A ${r} ${r} 0 ${large} 1 ${b.x} ${b.y}`
}

interface GaugeProps {
  /** 0–1 */
  value: number
  children: ReactNode
}

export function Gauge({ value, children }: GaugeProps) {
  const v = Math.min(1, Math.max(0, value))
  const end = START - SWEEP * v
  const knob = point(end, R)

  const ticks = []
  for (let deg = end - 4; deg >= START - SWEEP; deg -= 3.5) {
    const a = point(deg, R + 14)
    const b = point(deg, R + 26)
    ticks.push(<line key={deg} x1={a.x} y1={a.y} x2={b.x} y2={b.y} />)
  }

  return (
    <div className="relative mx-auto w-full max-w-[19rem]">
      <svg viewBox="0 0 300 245" className="w-full" aria-hidden="true">
        <g stroke="rgb(255 255 255 / 0.28)" strokeWidth="1.5" strokeLinecap="round">
          {ticks}
        </g>
        <path
          d={arc(START, START - SWEEP, R - 30)}
          fill="none"
          stroke="rgb(255 255 255 / 0.45)"
          strokeWidth="2.5"
          strokeLinecap="round"
          strokeDasharray="0.1 9"
        />
        {v > 0.002 && (
          <path
            d={arc(START, end, R)}
            fill="none"
            stroke="var(--color-accent)"
            strokeWidth="26"
            strokeLinecap="round"
          />
        )}
        <circle cx={knob.x} cy={knob.y} r="6" fill="var(--color-ink)" />
        <circle cx={knob.x} cy={knob.y} r="2.5" fill="var(--color-accent)" />
      </svg>
      <div className="absolute inset-x-0 top-[40%] flex flex-col items-center">{children}</div>
    </div>
  )
}
