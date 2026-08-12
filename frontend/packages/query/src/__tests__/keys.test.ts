import { describe, it, expect } from 'vitest';
import { queryKeys } from '../keys';

describe('queryKeys', () => {
  describe('courses', () => {
    it('all returns the base key', () => {
      expect(queryKeys.courses.all).toEqual(['courses']);
    });

    it('list includes params object', () => {
      const key = queryKeys.courses.list({ page: 1, pageSize: 20 });
      expect(key).toEqual(['courses', 'list', { page: 1, pageSize: 20 }]);
    });

    it('list with no params uses empty object', () => {
      expect(queryKeys.courses.list()).toEqual(['courses', 'list', {}]);
    });

    it('detail includes id', () => {
      expect(queryKeys.courses.detail('c1')).toEqual(['courses', 'detail', 'c1']);
    });
  });

  describe('user', () => {
    it('profile key', () => {
      expect(queryKeys.user.profile()).toEqual(['user', 'profile']);
    });

    it('enrollments key', () => {
      expect(queryKeys.user.enrollments()).toEqual(['user', 'enrollments']);
    });
  });

  describe('assignments', () => {
    it('list with courseId and params', () => {
      expect(queryKeys.assignments.list('c1', { page: 2 })).toEqual([
        'assignments',
        'list',
        'c1',
        { page: 2 },
      ]);
    });

    it('list without courseId uses "all"', () => {
      expect(queryKeys.assignments.list()).toEqual([
        'assignments',
        'list',
        'all',
        {},
      ]);
    });

    it('detail includes id', () => {
      expect(queryKeys.assignments.detail('a1')).toEqual(['assignments', 'detail', 'a1']);
    });
  });

  describe('live', () => {
    it('rooms with params', () => {
      expect(queryKeys.live.rooms({ page: 1 })).toEqual(['live', 'rooms', { page: 1 }]);
    });

    it('room detail', () => {
      expect(queryKeys.live.room('r1')).toEqual(['live', 'room', 'r1']);
    });
  });

  describe('uniqueness and hierarchy', () => {
    it('different params produce different keys', () => {
      expect(queryKeys.courses.list({ page: 1 })).not.toEqual(
        queryKeys.courses.list({ page: 2 }),
      );
    });

    it('same params produce equal keys (consistency)', () => {
      expect(queryKeys.courses.list({ page: 1, keyword: 'a' })).toEqual(
        queryKeys.courses.list({ page: 1, keyword: 'a' }),
      );
    });

    it('all key is prefix of list key (hierarchy)', () => {
      const all = queryKeys.courses.all;
      const list = queryKeys.courses.list({ page: 1 });
      expect(list.slice(0, all.length)).toEqual(all);
    });

    it('different entities do not collide', () => {
      expect(queryKeys.user.profile()).not.toEqual(queryKeys.user.enrollments());
      expect(queryKeys.courses.detail('a')).not.toEqual(queryKeys.assignments.detail('a'));
    });
  });
});
