// Value types the components take. Screens map API responses onto these; the
// components never import the generated client (C-4 contract shells).

export type Theme = 'light' | 'dark';

/** Portal is roomy, console is dense (tokens.md 3절). */
export type Density = 'portal' | 'console';

/** Who reads the label: customers see plain words, agents see short names (UX-3). */
export type Audience = 'customer' | 'agent';

export type IssueStatus = 'RECEIVED' | 'CONFIRMED' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED';

export type ProjectStatus = 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED';

/**
 * `INVITE_CANCELLED` is not an API status: screens map an account whose
 * invitation an admin cancelled (`cancelled: true`, S1A-AC-42) onto it.
 */
export type AccountStatus = 'ACTIVE' | 'INVITED' | 'INVITE_CANCELLED' | 'SUSPENDED';

export type Priority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT';

export interface Option<T extends string = string> {
  value: T;
  label: string;
  description?: string;
  disabled?: boolean;
}

/** One label of a company (slice 6a): the chip, the picker row and the list cell take it. */
export interface LabelItem {
  /** Label ID as text, like the values of `CheckboxGroup`. */
  id: string;
  /** 1~30 characters. */
  name: string;
  /** `#RRGGBB`, the background of the chip. */
  color: string;
}
