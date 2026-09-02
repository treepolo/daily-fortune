export const PARALLEL_MIN_DATE = "1900-01-01";
export const PARALLEL_MAX_DATE = "2100-12-31";

const DAY_MS = 86_400_000;
const MIN_DAY = Math.floor(Date.UTC(1900, 0, 1) / DAY_MS);
const MAX_DAY = Math.floor(Date.UTC(2100, 11, 31) / DAY_MS);
export const PARALLEL_SOURCE_DATE_COUNT = MAX_DAY - MIN_DAY + 1;

/**
 * Maps a zero-based index to every real calendar day in the supported range with no seasonal bias.
 */
export function sourceDateForIndex(index: number): string {
  if (!Number.isInteger(index) || index < 0 || index >= PARALLEL_SOURCE_DATE_COUNT) {
    throw new Error(`Parallel source-date index out of range: ${index}`);
  }
  return new Date((MIN_DAY + index) * DAY_MS).toISOString().slice(0, 10);
}

/**
 * Uniformly chooses one complete real sky from 1900-01-01 through 2100-12-31.
 * The original day itself is excluded so a reroll always moves to another worldline.
 */
export function randomParallelSourceDate(originalDate: string): string {
  let sourceDate: string;
  do {
    sourceDate = sourceDateForIndex(secureRandomInt(0, PARALLEL_SOURCE_DATE_COUNT - 1));
  } while (sourceDate === originalDate);
  return sourceDate;
}

function secureRandomInt(min: number, max: number): number {
  const range = max - min + 1;
  const maxUint = 0x1_0000_0000;
  const limit = Math.floor(maxUint / range) * range;
  const values = new Uint32Array(1);
  let value: number;
  do {
    crypto.getRandomValues(values);
    value = values[0];
  } while (value >= limit);
  return min + (value % range);
}
