import { create } from 'zustand';
import { apiClient } from '../api/apiClient';
import type { User } from '../types';

interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, displayName: string) => Promise<void>;
  logout: () => void;
  initializeAuth: () => void;
}

/**
 * Computed synchronously at store-creation time (before React's first render), not just inside
 * initializeAuth()'s useEffect — otherwise ProtectedRoute's very first render sees
 * isAuthenticated: false and redirects to /login before the effect ever restores the session,
 * which breaks a plain browser refresh (or any direct navigation) on every protected route.
 */
function loadStoredSession(): Pick<AuthState, 'user' | 'isAuthenticated'> {
  const token = localStorage.getItem('accessToken');
  const userId = localStorage.getItem('userId');
  const email = localStorage.getItem('email');
  const displayName = localStorage.getItem('displayName');
  if (token && userId && email) {
    return { user: { userId: parseInt(userId), email, displayName }, isAuthenticated: true };
  }
  return { user: null, isAuthenticated: false };
}

export const useAuthStore = create<AuthState>((set) => ({
  ...loadStoredSession(),
  isLoading: false,
  error: null,

  login: async (email, password) => {
    set({ isLoading: true, error: null });
    try {
      const res = await apiClient.login(email, password);
      const { userId, email: e, displayName, accessToken, refreshToken } = res.data;
      localStorage.setItem('accessToken', accessToken);
      localStorage.setItem('refreshToken', refreshToken);
      localStorage.setItem('userId', String(userId));
      localStorage.setItem('email', e);
      if (displayName) localStorage.setItem('displayName', displayName);
      set({ user: { userId, email: e, displayName }, isAuthenticated: true, isLoading: false });
    } catch (err: unknown) {
      const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message ?? 'Login failed';
      set({ error: message, isLoading: false });
      throw err;
    }
  },

  register: async (email, password, displayName) => {
    set({ isLoading: true, error: null });
    try {
      const res = await apiClient.register(email, password, displayName);
      const { userId, email: e, accessToken, refreshToken } = res.data;
      localStorage.setItem('accessToken', accessToken);
      localStorage.setItem('refreshToken', refreshToken);
      localStorage.setItem('userId', String(userId));
      localStorage.setItem('email', e);
      localStorage.setItem('displayName', displayName);
      set({ user: { userId, email: e, displayName }, isAuthenticated: true, isLoading: false });
    } catch (err: unknown) {
      const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message ?? 'Registration failed';
      set({ error: message, isLoading: false });
      throw err;
    }
  },

  logout: () => {
    localStorage.clear();
    set({ user: null, isAuthenticated: false, error: null });
  },

  initializeAuth: () => {
    set(loadStoredSession());
  },
}));
