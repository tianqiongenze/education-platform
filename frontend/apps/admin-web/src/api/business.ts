/**
 * API client functions for the SP-03 Business service.
 *
 * All endpoints are served under `/api/v1/...`. The shared `apiClient`
 * (see `src/api/client.ts`) prepends `apiBaseUrl` (from
 * `VITE_API_BASE_URL` or `/api`), so we pass the full path here.
 *
 * Each function is thin: it serializes the request, delegates to the shared
 * client, and returns a typed result. Errors surface as `ApiError` instances
 * from `@ep/api-client`, which callers can inspect with `isUnauthorized()`,
 * `isNotFound()`, etc.
 */
import { apiClient } from './client';
import { ApiError } from '@ep/api-client';
import type {
  User,
  CreateUserCmd,
  Course,
  CreateCourseCmd,
  Order,
  CreateOrderCmd,
  OrderStatus,
} from '../types/business';

export { ApiError };

// ---------------------------------------------------------------------------
// Users — /api/v1/users
// ---------------------------------------------------------------------------

/** POST /api/v1/users — register a new user. */
export async function createUser(cmd: CreateUserCmd): Promise<User> {
  return apiClient.post<User>('/api/v1/users', cmd);
}

/** GET /api/v1/users/{id} — fetch a single user. */
export async function getUserById(id: number): Promise<User> {
  return apiClient.get<User>(`/api/v1/users/${id}`);
}

/** GET /api/v1/users/phone/{phone} — fetch a user by phone number. */
export async function getUserByPhone(phone: string): Promise<User> {
  return apiClient.get<User>(`/api/v1/users/phone/${encodeURIComponent(phone)}`);
}

/** POST /api/v1/users/{id}/freeze — freeze an active user. */
export async function freezeUser(id: number, reason?: string): Promise<void> {
  await apiClient.post(`/api/v1/users/${id}/freeze`, { reason: reason ?? null });
}

/**
 * Unfreeze a user by updating the user's status back to ACTIVE.
 *
 * The backend does not expose a dedicated unfreeze endpoint, so we re-use
 * PUT /api/v1/users/{id} with an `UpdateUserCmd` carrying only the user id.
 * The service treats a no-op update as a status reset hook in
 * `UserServiceImpl.updateUser`.
 */
export async function unfreezeUser(id: number): Promise<void> {
  await apiClient.put(`/api/v1/users/${id}`, {
    userId: id,
    nickname: null,
    avatar: null,
    email: null,
  });
}

/** GET /api/v1/users — list users (mirrors `UserController` list semantics). */
export async function listUsers(): Promise<User[]> {
  return apiClient.get<User[]>('/api/v1/users');
}

// ---------------------------------------------------------------------------
// Courses — /api/v1/courses
// ---------------------------------------------------------------------------

/** POST /api/v1/courses — create a new course. */
export async function createCourse(cmd: CreateCourseCmd): Promise<Course> {
  return apiClient.post<Course>('/api/v1/courses', cmd);
}

/** GET /api/v1/courses/{id} — fetch a single course. */
export async function getCourseById(id: number): Promise<Course> {
  return apiClient.get<Course>(`/api/v1/courses/${id}`);
}

/** GET /api/v1/courses?page={page}&size={size} — list courses. */
export async function listCourses(page = 1, size = 10): Promise<Course[]> {
  return apiClient.get<Course[]>('/api/v1/courses', { page, size });
}

/** GET /api/v1/courses/search?keyword={keyword} — search courses by keyword. */
export async function searchCourses(keyword: string): Promise<Course[]> {
  return apiClient.get<Course[]>('/api/v1/courses/search', { keyword });
}

/** POST /api/v1/courses/{id}/publish — publish a DRAFT course. */
export async function publishCourse(id: number): Promise<void> {
  await apiClient.post(`/api/v1/courses/${id}/publish`);
}

/** POST /api/v1/courses/{id}/archive — archive a PUBLISHED course. */
export async function archiveCourse(id: number): Promise<void> {
  await apiClient.post(`/api/v1/courses/${id}/archive`);
}

// ---------------------------------------------------------------------------
// Orders — /api/v1/orders
// ---------------------------------------------------------------------------

/** POST /api/v1/orders — create a new order. */
export async function createOrder(cmd: CreateOrderCmd): Promise<Order> {
  return apiClient.post<Order>('/api/v1/orders', cmd);
}

/** GET /api/v1/orders/{id} — fetch a single order. */
export async function getOrderById(id: number): Promise<Order> {
  return apiClient.get<Order>(`/api/v1/orders/${id}`);
}

/** POST /api/v1/orders/{id}/pay — pay a PENDING order. */
export async function payOrder(id: number, payMethod?: string): Promise<void> {
  await apiClient.post(`/api/v1/orders/${id}/pay`, {
    payMethod: payMethod ?? null,
  });
}

/** POST /api/v1/orders/{id}/refund — refund a PAID order. */
export async function refundOrder(id: number, reason?: string): Promise<void> {
  await apiClient.post(`/api/v1/orders/${id}/refund`, {
    reason: reason ?? null,
  });
}

/** GET /api/v1/orders/user/{userId}?page={page}&size={size} — list orders for a user. */
export async function listUserOrders(
  userId: number,
  page = 1,
  size = 10,
): Promise<Order[]> {
  return apiClient.get<Order[]>(`/api/v1/orders/user/${userId}`, { page, size });
}

/**
 * List orders across the platform by fetching orders for each user.
 *
 * The SP-03 backend does not expose a global "list all orders" endpoint;
 * the only collection endpoint is `/api/v1/orders/user/{userId}`. To provide
 * a usable orders management view, we fetch the user list first and then
 * fan out one request per user, merging the results client-side. The
 * optional `status` filter is applied client-side as well.
 *
 * This is acceptable for the admin tool's expected data volume; if volumes
 * grow, a dedicated backend endpoint should be added.
 */
export async function listAllOrders(status?: OrderStatus): Promise<Order[]> {
  const users = await listUsers();
  const settled = await Promise.allSettled(
    users.map((u) => listUserOrders(u.userId, 1, 100)),
  );
  const merged: Order[] = [];
  settled.forEach((r) => {
    if (r.status === 'fulfilled') {
      merged.push(...r.value);
    }
  });
  // Sort newest first by createdAt (epoch ms), nulls last.
  merged.sort((a, b) => {
    const av = a.createdAt ?? 0;
    const bv = b.createdAt ?? 0;
    return bv - av;
  });
  if (status === undefined) return merged;
  return merged.filter((o) => o.status === status);
}
