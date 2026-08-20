import { Capacitor } from '@capacitor/core';

/**
 * True when running inside the native Android/iOS app shell, false in a plain browser tab.
 * Defensive on purpose — if the native bridge somehow isn't fully initialised when this is
 * first called, throwing here would take down the whole render tree with it.
 */
export const isNativePlatform = () => {
  try {
    return Capacitor.isNativePlatform();
  } catch (e) {
    console.error('[platform] isNativePlatform() threw, assuming web', e);
    return false;
  }
};
