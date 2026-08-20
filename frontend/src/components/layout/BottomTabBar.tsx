import { Link, useLocation } from 'react-router-dom';
import { Library, Search, ScanLine, ShoppingBag } from 'lucide-react';
import clsx from 'clsx';

const TABS = [
  { to: '/collections', label: 'Collections', icon: Library     },
  { to: '/items',       label: 'Catalogue',   icon: Search      },
  { to: '/scan',        label: 'Scan',        icon: ScanLine    },
  { to: '/thrift',      label: 'Thrift',      icon: ShoppingBag },
];

export function BottomTabBar() {
  const { pathname } = useLocation();

  return (
    <nav className="fixed bottom-0 left-0 right-0 z-50 bg-white border-t border-gray-200 flex safe-area-pb">
      {TABS.map(({ to, label, icon: Icon }) => {
        const active = pathname.startsWith(to);
        return (
          <Link
            key={to}
            to={to}
            className={clsx(
              'flex-1 flex flex-col items-center justify-center py-2 gap-0.5 text-xs font-medium transition-colors',
              active ? 'text-indigo-600' : 'text-gray-400 hover:text-gray-700'
            )}
          >
            <Icon size={22} strokeWidth={active ? 2.2 : 1.8} />
            <span>{label}</span>
          </Link>
        );
      })}
    </nav>
  );
}
