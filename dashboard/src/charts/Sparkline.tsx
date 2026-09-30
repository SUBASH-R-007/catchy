interface SparklineProps {
  values: number[];
  color?: string;
  width?: number;
  height?: number;
  /** Accessible description, e.g. "Hit rate over the last 15 minutes, now 91%". */
  ariaLabel: string;
  /** Fixed y-domain (e.g. [0, 100] for percentages); defaults to the data range. */
  domain?: [number, number];
}

/** 2px line + end dot, no axes. Purely supplementary — the tile's number carries the value. */
export function Sparkline({ values, color = 'var(--series-hit)', width = 120, height = 32, ariaLabel, domain }: SparklineProps) {
  const pad = 4;
  if (values.length < 2) {
    return <svg width={width} height={height} role="img" aria-label={`${ariaLabel} (not enough data)`} />;
  }
  const min = domain ? domain[0] : Math.min(...values);
  const max = domain ? domain[1] : Math.max(...values);
  const span = max - min || 1;
  const x = (i: number) => pad + (i * (width - pad * 2)) / (values.length - 1);
  const y = (v: number) => height - pad - ((v - min) / span) * (height - pad * 2);
  const d = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  const last = values.length - 1;
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={ariaLabel} className="sparkline">
      <path d={`${d} L${x(last).toFixed(1)},${height - pad} L${x(0).toFixed(1)},${height - pad} Z`} fill={color} opacity={0.1} />
      <path d={d} fill="none" stroke={color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={x(last)} cy={y(values[last] as number)} r={4} fill={color} stroke="var(--surface)" strokeWidth={2} />
    </svg>
  );
}
