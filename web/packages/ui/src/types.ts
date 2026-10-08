// Value types the components take. Screens map API responses onto these; the
// components never import the generated client (C-4 contract shells).

export type Theme = 'light' | 'dark';

/** Portal is roomy, console is dense (tokens.md 3절). */
export type Density = 'portal' | 'console';

/** Who reads the label: customers see plain words, agents see short names (UX-3). */
export type Audience = 'customer' | 'agent';

export type IssueStatus = 'RECEIVED' | 'CONFIRMED' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED';

export type ProjectStatus = 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED';

export type AccountStatus = 'ACTIVE' | 'INVITED' | 'SUSPENDED';

export type Priority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT';

export interface Option<T extends string = string> {
  value: T;
  label: string;
  description?: string;
  disabled?: boolean;
}
