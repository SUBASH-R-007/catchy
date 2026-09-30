import {
  Cable,
  Cpu,
  FlaskConical,
  History,
  LayoutDashboard,
  Trophy,
  type LucideIcon,
} from 'lucide-react';

export interface NavItem {
  path: string;
  label: string;
  icon: LucideIcon;
}

/** The six pages of the dashboard (SPEC 10.5), in sidebar order. */
export const NAV_ITEMS: readonly NavItem[] = [
  { path: '/', label: 'Overview', icon: LayoutDashboard },
  { path: '/race', label: 'Policy Race', icon: Trophy },
  { path: '/concurrency', label: 'Concurrency Lab', icon: Cpu },
  { path: '/playground', label: 'Playground', icon: FlaskConical },
  { path: '/replay', label: 'Trace Replay', icon: History },
  { path: '/integrations', label: 'Integrations', icon: Cable },
];
