import { Link, useLocation, useNavigate } from 'react-router-dom';
import { Library, BookOpen, Music, Film, Gamepad2, LogOut, User, Search, ScanLine, ShoppingBag, Download, Settings } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useMediaQuery } from '../../hooks/useMediaQuery';
import { BottomTabBar } from './BottomTabBar';
import { MobileHeader } from './MobileHeader';
import { isNativePlatform } from '../../utils/platform';
import clsx from 'clsx';
import type { ReactNode } from 'react';

const NAV = [
  { to: '/collections', label: 'Collections', icon: Library      },
  { to: '/items',       label: 'Catalogue',   icon: Search       },
  { to: '/scan',        label: 'Scanner',     icon: ScanLine     },
  { to: '/thrift',      label: 'Thrifting',   icon: ShoppingBag  },
  { to: '/settings',    label: 'Settings',    icon: Settings     },
];

export function AppLayout({ children }: { children: ReactNode }) {
  const location  = useLocation();
  const navigate  = useNavigate();
  const { user, logout } = useAuthStore();
  const isMobile  = useMediaQuery('(max-width: 767px)');

  if (isMobile) {
    return (
      <div className="app-shell min-h-screen bg-gray-50">
        <MobileHeader />
        {/* pt-12 clears the fixed top bar; pb-16 clears the fixed bottom tab bar */}
        <main className="pt-12 pb-16 min-h-screen">
          {children}
        </main>
        <BottomTabBar />
      </div>
    );
  }

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <div className="flex h-screen bg-gray-50">
      {/* Sidebar */}
      <aside className="w-60 bg-white border-r border-gray-200 flex flex-col shrink-0">
        {/* Logo */}
        <div className="flex items-center gap-2 px-5 py-4 border-b border-gray-200">
          <div className="flex items-center gap-1 text-indigo-600">
            <BookOpen size={18} />
            <Music size={16} />
            <Film size={18} />
            <Gamepad2 size={16} />
          </div>
          <span className="font-bold text-gray-900 text-base">BOCollections</span>
        </div>

        {/* Nav */}
        <nav className="flex-1 py-4 px-3 space-y-1">
          {NAV.map(({ to, label, icon: Icon }) => (
            <Link
              key={to}
              to={to}
              className={clsx(
                'flex items-center gap-3 px-3 py-2 rounded-lg text-sm font-medium transition-colors',
                location.pathname.startsWith(to)
                  ? 'bg-indigo-50 text-indigo-700'
                  : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
              )}
            >
              <Icon size={18} />
              {label}
            </Link>
          ))}
        </nav>

        {/* User */}
        <div className="px-3 py-4 border-t border-gray-200">
          <div className="flex items-center gap-3 px-3 py-2 rounded-lg text-sm text-gray-600">
            <User size={18} />
            <span className="truncate flex-1">{user?.displayName || user?.email}</span>
          </div>
          <button
            onClick={handleLogout}
            className="flex items-center gap-3 px-3 py-2 rounded-lg text-sm text-gray-500 hover:text-red-600 hover:bg-red-50 transition-colors w-full mt-1"
          >
            <LogOut size={18} />
            Sign out
          </button>
          {!isNativePlatform() && (
            <a
              href="https://pub-73de8ecb4a9644fc8072f4e6bb9c700a.r2.dev/bocollections/releases/latest/bocollections-debug.apk"
              download="bocollections-debug.apk"
              className="flex items-center gap-3 px-3 py-2 rounded-lg text-sm text-gray-400 hover:text-indigo-600 hover:bg-indigo-50 transition-colors w-full mt-1"
            >
              <Download size={18} />
              Android app (debug)
            </a>
          )}
        </div>
      </aside>

      {/* Main */}
      <main className="flex-1 overflow-auto">
        {children}
      </main>
    </div>
  );
}
