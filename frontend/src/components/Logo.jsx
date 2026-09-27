import { useId } from 'react'

export function LogoMark({ className = 'size-8' }) {
  const id = useId()
  return (
    <svg viewBox="0 0 32 32" className={className} aria-hidden>
      <defs>
        <linearGradient id={id} x1="4" y1="2" x2="28" y2="30" gradientUnits="userSpaceOnUse">
          <stop stopColor="#8e9cff" />
          <stop offset="1" stopColor="#2dd4ee" />
        </linearGradient>
      </defs>
      <path d="M16 2.5 5 6.6v8.1c0 7.1 4.7 12.7 11 14.8 6.3-2.1 11-7.7 11-14.8V6.6L16 2.5Z" fill={`url(#${id})`} />
      <circle cx="16" cy="15.5" r="5.2" fill="none" stroke="#05070b" strokeWidth="2.2" />
      <circle cx="16" cy="15.5" r="1.7" fill="#05070b" />
      <path d="M9.5 15.5h1.8M20.7 15.5h1.8" stroke="#05070b" strokeWidth="2.2" strokeLinecap="round" />
    </svg>
  )
}

export function Logo() {
  return (
    <span className="flex items-center gap-2.5">
      <LogoMark className="size-8 drop-shadow-[0_4px_14px_rgb(123_140_255/0.45)]" />
      <span className="leading-tight lg:max-xl:hidden">
        <span className="block text-[15px] font-semibold tracking-tight">Forged Media Detection</span>
        <span className="block text-[11px] text-muted tracking-wide">Trust &amp; Safety Console</span>
      </span>
    </span>
  )
}
