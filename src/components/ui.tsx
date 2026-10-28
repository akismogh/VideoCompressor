import type { ReactNode } from 'react'

// ── Icons (24px line icons, currentColor) ─────────────────────────────────────

const PATHS = {
  screen: 'M4 5h16v11H4zM9 20h6M12 16v4',
  camera: 'M3 7h11v10H3zM14 10.5 21 7v10l-7-3.5',
  library: 'M4 4h16v16H4zM4 15l4.5-4.5 4 4 2.5-2.5L20 17M15.5 8.5h.01',
  speaker: 'M4 9.5h3.5L12 6v12l-4.5-3.5H4zM15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11',
  mic: 'M12 4a2.5 2.5 0 0 0-2.5 2.5v5a2.5 2.5 0 0 0 5 0v-5A2.5 2.5 0 0 0 12 4zM6.5 11a5.5 5.5 0 0 0 11 0M12 16.5V20',
  back: 'M19 12H5M11 6l-6 6 6 6',
  scale: 'M12 4v16M5 8h14M5 8l-2.5 6a2.5 2.5 0 0 0 5 0zM19 8l-2.5 6a2.5 2.5 0 0 0 5 0zM8 20h8',
  share: 'M12 15V4M8 8l4-4 4 4M5 13v6h14v-6',
  download: 'M12 4v11M8 11l4 4 4-4M5 19h14',
  plus: 'M12 5v14M5 12h14',
  bolt: 'M13 3 5 14h6l-1 7 8-11h-6z',
  markIn: 'M6 5v14M10 12h9M14 8l-4 4 4 4',
  markOut: 'M18 5v14M14 12H5M10 8l4 4-4 4',
  film: 'M5 4h14v16H5zM5 8h3M5 12h3M5 16h3M16 8h3M16 12h3M16 16h3',
} as const

export type IconName = keyof typeof PATHS

export function Icon({ name, className = 'h-6 w-6' }: { name: IconName; className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className={className}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.7}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={PATHS[name]} />
    </svg>
  )
}

// ── Building blocks ───────────────────────────────────────────────────────────

/** Round icon button: neutral glass, or the neon accent for the main action. */
export function CircleButton({
  icon,
  label,
  onClick,
  accent = false,
  size = 'h-14 w-14',
  disabled = false,
}: {
  icon: IconName
  label: string
  onClick?: () => void
  accent?: boolean
  size?: string
  disabled?: boolean
}) {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      disabled={disabled}
      className={`${size} flex shrink-0 items-center justify-center rounded-full transition active:scale-95 disabled:opacity-40 ${
        accent ? 'bg-accent text-ink' : 'glass-strong text-white'
      }`}
    >
      <Icon name={icon} />
    </button>
  )
}

/** Two-line page title: light first line, bold second — as in the reference. */
export function Heading({ light, bold, sub }: { light: string; bold: string; sub?: ReactNode }) {
  return (
    <div>
      <h1 className="text-[2.5rem] leading-[1.05] tracking-tight">
        <span className="block font-light text-white/90">{light}</span>
        <span className="block font-semibold">{bold}</span>
      </h1>
      {sub && <p className="mt-2 text-[0.95rem] text-muted">{sub}</p>}
    </div>
  )
}

export function Toggle({
  checked,
  onChange,
  label,
}: {
  checked: boolean
  onChange: (checked: boolean) => void
  label: string
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={() => onChange(!checked)}
      className={`relative h-8 w-14 shrink-0 rounded-full transition-colors ${
        checked ? 'bg-accent' : 'bg-white/20'
      }`}
    >
      <span
        className={`absolute top-1 h-6 w-6 rounded-full transition-all ${
          checked ? 'left-7 bg-ink' : 'left-1 bg-white'
        }`}
      />
    </button>
  )
}

/** Settings tile: icon + label on top, switch and ON/OFF below (reference's lower grid). */
export function ToggleTile({
  icon,
  label,
  checked,
  onChange,
  disabled = false,
}: {
  icon: IconName
  label: string
  checked: boolean
  onChange: (checked: boolean) => void
  disabled?: boolean
}) {
  return (
    <div
      className={`glass flex flex-col justify-between gap-5 rounded-[1.75rem] p-3 ${disabled ? 'opacity-40' : ''}`}
    >
      <div className="flex items-center gap-2">
        <span className="glass-strong flex h-10 w-10 shrink-0 items-center justify-center rounded-full">
          <Icon name={icon} className="h-5 w-5" />
        </span>
        <span className="min-w-0 text-[0.9rem] leading-tight">{label}</span>
      </div>
      <div className="flex items-center justify-between">
        <Toggle checked={checked} onChange={disabled ? () => {} : onChange} label={label} />
        <span className="text-xl font-light">{checked ? 'ON' : 'OFF'}</span>
      </div>
    </div>
  )
}

export function PillButton({
  children,
  onClick,
  accent = false,
  disabled = false,
  className = '',
}: {
  children: ReactNode
  onClick?: () => void
  accent?: boolean
  disabled?: boolean
  className?: string
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`flex h-16 items-center justify-center gap-2.5 rounded-full px-6 text-lg font-medium transition active:scale-[0.98] disabled:opacity-40 ${
        accent ? 'bg-accent text-ink' : 'glass-strong text-white'
      } ${className}`}
    >
      {children}
    </button>
  )
}
