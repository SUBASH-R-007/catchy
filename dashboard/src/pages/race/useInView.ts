import { useEffect, useState, type RefObject } from 'react';

/**
 * True while the element is (at least partly) inside the viewport. Browsers without
 * IntersectionObserver (and jsdom) report true, so polling still works there.
 */
export function useInView(ref: RefObject<Element | null>): boolean {
  const supported = typeof IntersectionObserver !== 'undefined';
  const [inView, setInView] = useState(true);

  useEffect(() => {
    const element = ref.current;
    if (!supported || !element) return;
    const observer = new IntersectionObserver((entries) => {
      const entry = entries[entries.length - 1];
      if (entry) setInView(entry.isIntersecting);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [ref, supported]);

  return inView;
}
