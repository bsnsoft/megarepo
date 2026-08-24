import { describe, expect, it } from 'vitest';
import { ApiError } from './client';
import { durationSeconds, requiredConfirmationFrom, unwrapList } from './firewall';

describe('unwrapList', () => {
  it('takes a bare array', () => {
    expect(unwrapList<number>([1, 2])).toEqual([1, 2]);
  });

  it('takes the app PageResponse', () => {
    expect(unwrapList<number>({ items: [1], continuationToken: null })).toEqual([1]);
  });

  it('takes a Spring Page', () => {
    expect(unwrapList<number>({ content: [7], totalElements: 1 })).toEqual([7]);
  });

  // The whole reason this function exists: B2 writes the controllers, and a list
  // shape nobody agreed on must not take a screen down.
  it('answers empty for anything else rather than throwing', () => {
    expect(unwrapList(null)).toEqual([]);
    expect(unwrapList('nope')).toEqual([]);
    expect(unwrapList({ unexpected: true })).toEqual([]);
  });
});

describe('durationSeconds', () => {
  it('reads Jackson default (seconds as a number)', () => {
    expect(durationSeconds(2_592_000)).toBe(2_592_000);
  });

  it('reads ISO-8601, which is what write-durations-as-timestamps=false produces', () => {
    expect(durationSeconds('P30D')).toBe(2_592_000);
    expect(durationSeconds('PT720H')).toBe(2_592_000);
    expect(durationSeconds('PT1H30M')).toBe(5400);
    expect(durationSeconds('PT30S')).toBe(30);
    expect(durationSeconds('P1DT2H')).toBe(93_600);
  });

  it('reads a numeric string', () => {
    expect(durationSeconds('604800')).toBe(604_800);
  });

  it('answers null on nothing usable', () => {
    expect(durationSeconds(null)).toBeNull();
    expect(durationSeconds(undefined)).toBeNull();
    expect(durationSeconds('')).toBeNull();
    expect(durationSeconds('P')).toBeNull();
    expect(durationSeconds('yesterday')).toBeNull();
  });
});

describe('requiredConfirmationFrom', () => {
  function validation(message: string, status = 400): ApiError {
    return new ApiError(status, message, {
      status,
      error: 'Bad Request',
      message,
      timestamp: '2026-01-01T00:00:00Z',
    });
  }

  it('reads the phrase out of the message the server actually sends', () => {
    const error = validation(
      'This turns on blocking and can fail builds. To confirm, send confirmation="ENABLE ENFORCEMENT".',
    );
    expect(requiredConfirmationFrom(error)).toBe('ENABLE ENFORCEMENT');
  });

  it('reads a phrase naming a repository', () => {
    expect(
      requiredConfirmationFrom(validation('send confirmation="QUARANTINE maven-proxy".')),
    ).toBe('QUARANTINE maven-proxy');
  });

  it('works on a 409 too', () => {
    expect(requiredConfirmationFrom(validation('confirmation="REPLACE POLICY"', 409))).toBe(
      'REPLACE POLICY',
    );
  });

  it('reads an unquoted phrase', () => {
    expect(requiredConfirmationFrom(validation('send confirmation=ENABLE ENFORCEMENT.'))).toBe(
      'ENABLE ENFORCEMENT',
    );
  });

  it('answers null when the failure is not about a confirmation', () => {
    expect(requiredConfirmationFrom(validation('expiresAt must be in the future'))).toBeNull();
    expect(requiredConfirmationFrom(new Error('boom'))).toBeNull();
    expect(requiredConfirmationFrom(validation('nope', 500))).toBeNull();
  });
});
