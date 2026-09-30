import { useEffect, useRef, useState, type RefObject } from 'react';

/**
 * Tracks the rendered width of a container so SVG charts can draw in real pixels
 * (keeps text and marks crisp instead of scaling them with a viewBox). Falls back to
 * `fallback` where ResizeObserver is unavailable (jsdom, very old browsers).
 */
export function useChartWidth<T extends HTMLElement>(fallback = 640): [RefObject<T | null>, number] {
  const ref = useRef<T | null>(null);
  const [width, setWidth] = useState(fallback);

  useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    const measure = () => {
      const w = el.getBoundingClientRect().width;
      if (w > 0) setWidth(Math.round(w));
    };
    measure();
    if (typeof ResizeObserver === 'undefined') return undefined;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  return [ref, width];
}
