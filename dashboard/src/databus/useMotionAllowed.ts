import { useSyncExternalStore } from 'react';
import { usePrefersReducedMotion } from '../components';

function subscribeToAnimationFlag(onChange: () => void): () => void {
  if (typeof MutationObserver === 'undefined') return () => {};
  const observer = new MutationObserver(onChange);
  observer.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['data-animation'],
  });
  return () => observer.disconnect();
}

function animationFlagOn(): boolean {
  return document.documentElement.dataset.animation !== 'off';
}

function animationFlagOnServer(): boolean {
  return true;
}

/**
 * True when moving decorations may run: the persisted "Circuit animation" toggle is on
 * (`html[data-animation]` is not "off") and the OS does not ask for reduced motion.
 */
export function useMotionAllowed(): boolean {
  const reduced = usePrefersReducedMotion();
  const flagOn = useSyncExternalStore(
    subscribeToAnimationFlag,
    animationFlagOn,
    animationFlagOnServer,
  );
  return flagOn && !reduced;
}
