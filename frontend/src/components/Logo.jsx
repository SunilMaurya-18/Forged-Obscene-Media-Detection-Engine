import { useId } from 'react'

export function LogoMark({ className = 'size-8' }) {
  const id = useId()
  return (
    <svg viewBox="0 0 32 32" className={className} aria-hidden>
      <defs>
        <linearGradient id={id} x1="16" y1="2.5" x2="16" y2="29.5" gradientUnits="userSpaceOnUse">
          <stop stopColor="#f4f6f8" />
          <stop offset="0.42" stopColor="#c3c9cf" />
          <stop offset="0.5" stopColor="#7f878f" />
          <stop offset="0.58" stopColor="#d2d7dc" />
          <stop offset="1" stopColor="#9aa1a9" />
        </linearGradient>
      </defs>
      <path d="M16 2.5 5 6.6v8.1c0 7.1 4.7 12.7 11 14.8 6.3-2.1 11-7.7 11-14.8V6.6L16 2.5Z" fill={`url(#${id})`} />
      <circle cx="16" cy="15.5" r="5.2" fill="none" stroke="#0f1114" strokeWidth="2.2" />
      <circle cx="16" cy="15.5" r="1.9" fill="#d97757" />
      <path d="M9.5 15.5h1.8M20.7 15.5h1.8" stroke="#0f1114" strokeWidth="2.2" strokeLinecap="round" />
    </svg>
  )
}

/** `always`: keep the wordmark at every width (the sidebar); otherwise it steps aside while the top nav needs the room. */
export function Logo({ always = false }) {
  return (
    <span className="flex items-center gap-2.5">
      <LogoMark className="size-8 shrink-0 drop-shadow-[0_4px_10px_rgb(0_0_0/0.45)]" />
      <span className={always ? 'leading-tight min-w-0' : 'leading-tight lg:max-xl:hidden'}>
        <span className="flex items-baseline min-w-0 font-serif text-[15px] font-medium tracking-[-0.01em]">
          <span className="truncate">Forged Media Detection</span>
          <span aria-hidden className="ml-[3px] inline-block size-[5px] shrink-0 rounded-full bg-brand" />
        </span>
        <span className="block font-mono text-[10px] uppercase tracking-[0.08em] text-subtle truncate">Trust &amp; Safety Console</span>
      </span>
    </span>
  )
}
