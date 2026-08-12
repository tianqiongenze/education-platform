/**
 * TypeScript interfaces for the SP-03 Business service.
 *
 * These types mirror the Java entities and DTOs defined in
 * services/sp03-business/src/main/java/com/ep/business.
 *
 * Notes:
 * - Entity IDs are numeric (`number`) because the backend uses Java `Long`.
 * - Timestamps are epoch milliseconds (Java `Long`) rather than ISO-8601,
 *   because the business entities use `Long createdAt/updatedAt`.
 * - Status fields are numeric enum codes mirroring the Java enums
 *   (`UserStatus`, `CourseStatus`, `OrderStatus`).
 */

// ---------------------------------------------------------------------------
// User — com.ep.business.user
// ---------------------------------------------------------------------------

/**
 * Mirrors `com.ep.business.user.UserStatus`.
 * - DISABLED(0)
 * - ACTIVE(1)
 * - FROZEN(2)
 */
export type UserStatus = 0 | 1 | 2;

export interface User {
  userId: number;
  username: string;
  phone: string;
  email: string;
  /** Opaque password hash; rarely populated in API responses. */
  passwordHash: string | null;
  nickname: string | null;
  avatar: string | null;
  status: UserStatus;
  tenantId: number | null;
  /** Epoch milliseconds. */
  createdAt: number | null;
  /** Epoch milliseconds. */
  updatedAt: number | null;
}

/**
 * Request payload for POST /api/v1/users.
 * Mirrors the Java class `CreateUserCmd`.
 */
export interface CreateUserCmd {
  username: string;
  phone: string;
  email: string;
  password: string;
  nickname: string;
}

/**
 * Request payload for PUT /api/v1/users/{id}.
 * Mirrors the Java class `UpdateUserCmd`.
 */
export interface UpdateUserCmd {
  userId: number;
  nickname: string | null;
  avatar: string | null;
  email: string | null;
}

// ---------------------------------------------------------------------------
// Course — com.ep.business.course
// ---------------------------------------------------------------------------

/**
 * Mirrors `com.ep.business.course.CourseStatus`.
 * - DRAFT(0)
 * - PUBLISHED(1)
 * - ARCHIVED(2)
 */
export type CourseStatus = 0 | 1 | 2;

export interface Course {
  courseId: number;
  title: string;
  description: string | null;
  teacherId: number;
  category: number | null;
  difficulty: number | null;
  /** Decimal price as a string to preserve precision (Java `BigDecimal`). */
  price: string | null;
  status: CourseStatus;
  studentCount: number | null;
  /** Epoch milliseconds. */
  createdAt: number | null;
  /** Epoch milliseconds. */
  updatedAt: number | null;
}

/**
 * Request payload for POST /api/v1/courses.
 * Mirrors the Java class `CreateCourseCmd`.
 */
export interface CreateCourseCmd {
  title: string;
  description: string | null;
  teacherId: number;
  category: number | null;
  difficulty: number | null;
  /** Decimal price as a string to preserve precision (Java `BigDecimal`). */
  price: string | null;
}

// ---------------------------------------------------------------------------
// Order — com.ep.business.order
// ---------------------------------------------------------------------------

/**
 * Mirrors `com.ep.business.order.OrderStatus`.
 * - PENDING(0)
 * - PAID(1)
 * - REFUNDED(2)
 * - CANCELLED(3)
 */
export type OrderStatus = 0 | 1 | 2 | 3;

export interface Order {
  orderId: number;
  userId: number;
  courseId: number;
  /** Decimal amount as a string to preserve precision (Java `BigDecimal`). */
  amount: string | null;
  status: OrderStatus;
  payMethod: string | null;
  idempotencyKey: string | null;
  /** Epoch milliseconds. */
  createdAt: number | null;
  /** Epoch milliseconds. */
  updatedAt: number | null;
}

/**
 * Request payload for POST /api/v1/orders.
 * Mirrors the Java class `CreateOrderCmd`.
 */
export interface CreateOrderCmd {
  userId: number;
  courseId: number;
  /** Decimal amount as a string to preserve precision (Java `BigDecimal`). */
  amount: string | null;
  idempotencyKey: string;
}

// ---------------------------------------------------------------------------
// Zod schemas — used for runtime validation of API responses in detail pages.
// ---------------------------------------------------------------------------

import { z } from 'zod';

export const userStatusSchema = z.union([z.literal(0), z.literal(1), z.literal(2)]);
export const courseStatusSchema = z.union([z.literal(0), z.literal(1), z.literal(2)]);
export const orderStatusSchema = z.union([
  z.literal(0),
  z.literal(1),
  z.literal(2),
  z.literal(3),
]);

export const userSchema = z.object({
  userId: z.number(),
  username: z.string(),
  phone: z.string(),
  email: z.string(),
  passwordHash: z.string().nullable(),
  nickname: z.string().nullable(),
  avatar: z.string().nullable(),
  status: userStatusSchema,
  tenantId: z.number().nullable(),
  createdAt: z.number().nullable(),
  updatedAt: z.number().nullable(),
});

export const courseSchema = z.object({
  courseId: z.number(),
  title: z.string(),
  description: z.string().nullable(),
  teacherId: z.number(),
  category: z.number().nullable(),
  difficulty: z.number().nullable(),
  price: z.string().nullable(),
  status: courseStatusSchema,
  studentCount: z.number().nullable(),
  createdAt: z.number().nullable(),
  updatedAt: z.number().nullable(),
});

export const orderSchema = z.object({
  orderId: z.number(),
  userId: z.number(),
  courseId: z.number(),
  amount: z.string().nullable(),
  status: orderStatusSchema,
  payMethod: z.string().nullable(),
  idempotencyKey: z.string().nullable(),
  createdAt: z.number().nullable(),
  updatedAt: z.number().nullable(),
});
