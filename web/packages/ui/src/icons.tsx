// Icon assets (screens.md 5절: icons are assets, not components). Every icon is
// decorative: `aria-hidden` and not focusable. The name always comes from text
// or from the control's `aria-label`.
import type { ReactNode } from 'react';

interface IconProps {
  /** px. Default 16. */
  size?: number;
}

function Svg(props: IconProps & { children: ReactNode; fill?: boolean }) {
  const size = props.size ?? 16;
  return (
    <svg
      className="pd-icon"
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill={props.fill ? 'currentColor' : 'none'}
      stroke={props.fill ? 'none' : 'currentColor'}
      strokeWidth={1.5}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {props.children}
    </svg>
  );
}

export function LockIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <rect x="3.25" y="7" width="9.5" height="6.5" rx="1.25" />
      <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" />
    </Svg>
  );
}

export function EyeIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M1.5 8s2.4-4.5 6.5-4.5S14.5 8 14.5 8 12.1 12.5 8 12.5 1.5 8 1.5 8Z" />
      <circle cx="8" cy="8" r="2" />
    </Svg>
  );
}

export function EyeOffIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M2.5 2.5l11 11" />
      <path d="M6.2 4A6.6 6.6 0 0 1 8 3.5c4.1 0 6.5 4.5 6.5 4.5a11 11 0 0 1-1.9 2.4M9.8 12.1a6.4 6.4 0 0 1-1.8.4C3.9 12.5 1.5 8 1.5 8a11.2 11.2 0 0 1 2.4-2.8" />
    </Svg>
  );
}

export function ChevronDownIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M4 6l4 4 4-4" />
    </Svg>
  );
}

/** Three dots: a row's menu of actions. */
export function MoreIcon(props: IconProps) {
  return (
    <Svg {...props} fill>
      <circle cx="3" cy="8" r="1.25" />
      <circle cx="8" cy="8" r="1.25" />
      <circle cx="13" cy="8" r="1.25" />
    </Svg>
  );
}

export function CloseIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M4 4l8 8M12 4l-8 8" />
    </Svg>
  );
}

export function FileIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M9 1.75H4.5a1 1 0 0 0-1 1v10.5a1 1 0 0 0 1 1h7a1 1 0 0 0 1-1V5.25Z" />
      <path d="M9 1.75v3.5h3.5" />
    </Svg>
  );
}

export function CopyIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <rect x="5.25" y="5.25" width="8.5" height="8.5" rx="1.25" />
      <path d="M10.75 5.25V3.5a1.25 1.25 0 0 0-1.25-1.25h-6A1.25 1.25 0 0 0 2.25 3.5v6a1.25 1.25 0 0 0 1.25 1.25h1.75" />
    </Svg>
  );
}

export function UploadIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M8 10.5V2.5M5 5.5l3-3 3 3" />
      <path d="M2.5 10.5v2a1 1 0 0 0 1 1h9a1 1 0 0 0 1-1v-2" />
    </Svg>
  );
}

export function WarningIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M8 2.25 14.25 13.5H1.75Z" />
      <path d="M8 6.5v3M8 11.6v.05" />
    </Svg>
  );
}

export function InfoIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <circle cx="8" cy="8" r="6.25" />
      <path d="M8 7.25v4M8 4.85v.05" />
    </Svg>
  );
}

export function CheckCircleIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <circle cx="8" cy="8" r="6.25" />
      <path d="M5.25 8.25 7.1 10l3.65-3.75" />
    </Svg>
  );
}

export function CheckIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M3.5 8.5 6.5 11.5 12.5 4.5" />
    </Svg>
  );
}

export function ExclamationIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M8 3v6.5M8 12.5v.05" strokeWidth={2} />
    </Svg>
  );
}

export function MenuIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M2.5 4h11M2.5 8h11M2.5 12h11" />
    </Svg>
  );
}

export function SidebarIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <rect x="1.75" y="2.75" width="12.5" height="10.5" rx="1.5" />
      <path d="M6 2.75v10.5" />
    </Svg>
  );
}

export function ChatIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M2.5 3.5a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v6.5a1 1 0 0 1-1 1H7l-3 2.5v-2.5h-.5a1 1 0 0 1-1-1Z" />
    </Svg>
  );
}

export function InboxIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M1.75 9h3.5l1 1.75h3.5l1-1.75h3.5" />
      <path d="M3.5 3h9l1.75 6v3.5a1 1 0 0 1-1 1H2.75a1 1 0 0 1-1-1V9Z" />
    </Svg>
  );
}

export function SearchOffIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <circle cx="7" cy="7" r="4.5" />
      <path d="M10.4 10.4 14 14M5.25 5.25l3.5 3.5M8.75 5.25l-3.5 3.5" />
    </Svg>
  );
}

/** Priority bars: `count` of three filled, the rest faint (tokens.md 7절). */
export function BarsIcon(props: IconProps & { count: 1 | 2 | 3 }) {
  const size = props.size ?? 14;
  return (
    <svg
      className="pd-icon pd-icon-bars"
      width={size}
      height={size}
      viewBox="0 0 14 14"
      aria-hidden="true"
      focusable="false"
    >
      {[0, 1, 2].map((i) => (
        <rect
          key={i}
          x={1.5 + i * 4}
          y={9 - i * 3}
          width="3"
          height={3.5 + i * 3}
          rx="0.75"
          fill="currentColor"
          opacity={i < props.count ? 1 : 0.25}
        />
      ))}
    </svg>
  );
}

/** Spinner for 보내는 중 and 모름 progress. Stops under reduced motion (CSS). */
export function Spinner(props: IconProps) {
  const size = props.size ?? 16;
  return (
    <svg className="pd-spinner" width={size} height={size} viewBox="0 0 16 16" aria-hidden="true" focusable="false">
      <circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" strokeOpacity="0.25" strokeWidth="2" />
      <path d="M14 8a6 6 0 0 0-6-6" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}
