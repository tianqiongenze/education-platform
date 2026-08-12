import { z } from 'zod';

export const loginFormSchema = z.object({
  username: z.string().min(3, '用户名至少3个字符'),
  password: z.string().min(8, '密码至少8个字符'),
});

export type LoginForm = z.infer<typeof loginFormSchema>;

export const courseSearchFormSchema = z.object({
  keyword: z.string().trim().max(100).optional(),
  tags: z.array(z.string()).max(20).optional(),
  status: z.enum(['draft', 'published', 'archived']).optional(),
  sortBy: z.enum(['newest', 'rating', 'price_asc', 'price_desc']).optional(),
  page: z.number().int().positive().default(1),
  pageSize: z.number().int().positive().max(100).default(20),
});

export type CourseSearchForm = z.infer<typeof courseSearchFormSchema>;

export const profileEditFormSchema = z.object({
  nickname: z.string().min(1, '昵称不能为空').max(32, '昵称最多32个字符'),
  avatar: z.string().url('头像必须是有效的URL').or(z.literal('')),
  bio: z.string().max(500, '简介最多500个字符').optional(),
  email: z.string().email('邮箱格式不正确').or(z.literal('')).optional(),
  phone: z
    .string()
    .regex(/^1[3-9]\d{9}$/, '手机号格式不正确')
    .or(z.literal(''))
    .optional(),
});

export type ProfileEditForm = z.infer<typeof profileEditFormSchema>;
