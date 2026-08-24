/**
 * Time and text formatting shared by the firewall screens.
 *
 * The queue's most-read column is "when does this get out", so relative time is
 * a first-class concern here rather than a nicety: an operator scanning forty
 * held components needs "in 6 h", not a timestamp they have to subtract from now.
 */

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

export function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) {
    return '—';
  }
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString();
}

/** "3 d", "6 h", "12 min" — the magnitude, without a unit salad. */
export function formatSpan(millis: number): string {
  const abs = Math.abs(millis);
  if (abs < MINUTE) {
    return 'under a minute';
  }
  if (abs < HOUR) {
    return `${Math.round(abs / MINUTE)} min`;
  }
  if (abs < DAY) {
    return `${Math.round(abs / HOUR)} h`;
  }
  return `${Math.round(abs / DAY)} d`;
}

/** "in 6 h" / "6 h ago" / "—". `now` is injectable so tests do not race a clock. */
export function formatRelative(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) {
    return '—';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  const delta = date.getTime() - now.getTime();
  return delta >= 0 ? `in ${formatSpan(delta)}` : `${formatSpan(delta)} ago`;
}

export function formatAge(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) {
    return '—';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  return formatSpan(now.getTime() - date.getTime());
}

/** Seconds → a `datetime-local` value that many days/hours from now. */
export function isoFromNowSeconds(seconds: number, now: Date = new Date()): string {
  return new Date(now.getTime() + seconds * 1000).toISOString();
}

/**
 * `datetime-local` speaks local wall-clock without a zone; the API speaks
 * Instant. These two conversions are the only place that gap is bridged.
 */
export function toLocalInputValue(iso: string | null | undefined): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const offset = date.getTimezoneOffset() * MINUTE;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

export function fromLocalInputValue(value: string): string | null {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** Turns SCREAMING_SNAKE into "Screaming snake" for enum labels. */
export function humanizeEnum(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const spaced = value.replace(/_/g, ' ').toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}
