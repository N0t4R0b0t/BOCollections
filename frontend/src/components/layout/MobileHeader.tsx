import { useNavigate } from 'react-router-dom';
import { BookOpen, Music, Film, Gamepad2, LogOut, Download } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { isNativePlatform } from '../../utils/platform';

export function MobileHeader() {
  const { user, logout } = useAuthStore();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  // User initial for avatar
  const initial = (user?.displayName ?? user?.email ?? '?')[0].toUpperCase();

  return (
    <header className="fixed top-0 left-0 right-0 z-50 bg-white border-b border-gray-200 h-12 flex items-center justify-between px-4">
      <div className="flex items-center gap-1 text-indigo-600">
        <BookOpen size={15} />
        <Music size={13} />
        <Film size={15} />
        <Gamepad2 size={13} />
        <span className="font-bold text-gray-900 text-sm ml-1.5">BOCollections</span>
      </div>

      <div className="flex items-center gap-3">
        {/* Debug APK sideload link — only useful in a plain browser, not inside the app itself */}
        {!isNativePlatform() && (
          <a
            href="https://pub-73de8ecb4a9644fc8072f4e6bb9c700a.r2.dev/bocollections/releases/latest/bocollections-debug.apk"
            download="bocollections-debug.apk"
            title="Download Android app (debug build)"
            className="text-gray-400 hover:text-indigo-600 transition-colors p-1"
          >
            <Download size={18} />
          </a>
        )}
        {/* User initial badge */}
        <div className="w-7 h-7 rounded-full bg-indigo-100 text-indigo-700 text-xs font-bold flex items-center justify-center">
          {initial}
        </div>
        <button
          onClick={handleLogout}
          className="text-gray-400 hover:text-red-500 transition-colors p-1"
          aria-label="Sign out"
        >
          <LogOut size={18} />
        </button>
      </div>
    </header>
  );
}
