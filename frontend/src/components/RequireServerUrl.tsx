import { Navigate } from 'react-router-dom';
import type { ReactNode } from 'react';
import { isNativePlatform } from '../utils/platform';
import { getServerUrl } from '../utils/serverUrl';

/**
 * Gates the pre-auth entry points (login/register) on native only — web is always served by
 * its own backend, so it never needs this. Without a configured server, login would just fail
 * against a meaningless relative /api that resolves inside the app's local WebView origin.
 */
export function RequireServerUrl({ children }: { children: ReactNode }) {
  if (isNativePlatform() && !getServerUrl()) return <Navigate to="/connect" replace />;
  return <>{children}</>;
}
