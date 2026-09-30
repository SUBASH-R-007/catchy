import { formatBytes, formatPercent } from '../lib/format';
import { MEMORY_THRESHOLDS, METER_SEVERITY_LABEL, memorySeverity, type MeterSeverity } from '../lib/health';

interface MeterProps {
  /** 0..100 */
  percent: number;
  /** Accessible name, e.g. "Memory utilization". */
  label: string;
  /** Text shown to the right of the bar (defaults to the percentage). */
  valueText?: string;
  /** Show the 80 / 90 / 98 threshold ticks on the track. */
  showTicks?: boolean;
  /** Also spell the severity out in text next to the bar (never color alone). */
  showSeverity?: boolean;
  size?: 'sm' | 'md';
}

/**
 * Single-ratio meter. The fill color escalates at 80 / 90 / 98 percent (blue → amber → orange → red)
 * while the track is a lighter step of the same scale; severity is also available as text.
 */
export function Meter({ percent, label, valueText, showTicks = false, showSeverity = false, size = 'md' }: MeterProps) {
  const pct = Math.min(100, Math.max(0, Number.isFinite(percent) ? percent : 0));
  const severity: MeterSeverity = memorySeverity(pct);
  const severityText = METER_SEVERITY_LABEL[severity];
  const text = valueText ?? formatPercent(pct);
  return (
    <div className={`meter meter--${size}`}>
      <div
        className="meter__track"
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(pct * 100) / 100}
        aria-valuetext={text ? `${text} — ${severityText}` : `${formatPercent(pct)} — ${severityText}`}
      >
        <div className={`meter__fill meter__fill--${severity}`} style={{ width: `${pct}%` }} />
        {showTicks
          ? [MEMORY_THRESHOLDS.warning, MEMORY_THRESHOLDS.serious, MEMORY_THRESHOLDS.critical].map((t) => (
              <span key={t} className="meter__tick" style={{ left: `${t}%` }} aria-hidden="true" />
            ))
          : null}
      </div>
      <div className="meter__text num">
        {text}
        {showSeverity && severity !== 'ok' ? <span className={`meter__severity meter__severity--${severity}`}>{severityText}</span> : null}
      </div>
    </div>
  );
}

interface MemoryMeterProps {
  usedBytes: number;
  maxBytes: number;
  percent: number;
  showTicks?: boolean;
  showSeverity?: boolean;
  size?: 'sm' | 'md';
}

/** Memory utilization meter with "132 MB / 256 MB" text. All values are *estimated* cache memory. */
export function MemoryMeter({ usedBytes, maxBytes, percent, showTicks, showSeverity, size }: MemoryMeterProps) {
  return (
    <Meter
      percent={percent}
      label="Estimated memory utilization"
      valueText={`${formatBytes(usedBytes)} / ${formatBytes(maxBytes)}`}
      showTicks={showTicks}
      showSeverity={showSeverity}
      size={size}
    />
  );
}
