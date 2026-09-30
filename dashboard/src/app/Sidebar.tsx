import { NavLink } from 'react-router';
import { NAV_ITEMS } from './navigation';

/** Icon sidebar; labels collapse to icons only below 1100 px (SPEC 10.5). */
export function Sidebar() {
  return (
    <nav
      aria-label="Main"
      className="sticky top-0 flex h-screen w-16 shrink-0 flex-col gap-2 border-r border-trace bg-surface px-2 py-4 min-[1100px]:w-56"
    >
      <ul className="flex flex-col gap-1">
        {NAV_ITEMS.map(({ path, label, icon: Icon }) => (
          <li key={path}>
            <NavLink
              to={path}
              end={path === '/'}
              title={label}
              className={({ isActive }) =>
                [
                  'flex items-center gap-3 rounded-md px-3 py-2 text-sm transition-colors',
                  isActive
                    ? 'bg-surface-2 text-text shadow-[inset_2px_0_0_var(--trace-glow)]'
                    : 'text-muted hover:bg-surface-2 hover:text-text',
                ].join(' ')
              }
            >
              <Icon aria-hidden="true" className="size-5 shrink-0" />
              <span className="sr-only min-[1100px]:not-sr-only">{label}</span>
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}
