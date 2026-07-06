# Flicklog Server - Spring Boot port

A 1:1 functional port of the Express/Mongoose `server/` backend to Spring Boot 3 (Java 17),
using Spring Data MongoDB (not JPA - your schema is document-shaped, not relational),
Spring Data Redis, Spring Security + JJWT, and the Cloudinary Java SDK.

## Running it

1. Copy `.env.example` to `.env` (or just export the same variables) and fill in real values.
2. Have MongoDB and Redis reachable at the URLs you configure.
3. `mvn spring-boot:run`

Server listens on `PORT` (default 5000), same as the original. There's no HTTPS/dev-cert
handling in this version (the original's `certs/key.pem` + `certs/cert.pem` dev-HTTPS
branch) - terminate TLS at a reverse proxy (nginx, Render, etc.) in front of this, which is
almost certainly what you want in front of a JAR anyway.

## Endpoint mapping

| Original (Express)                          | Spring Boot                                  |
|----------------------------------------------|-----------------------------------------------|
| `POST /auth/register`                        | `AuthController#register`                     |
| `POST /auth/login`                            | `AuthController#login`                        |
| `GET /auth/refresh-token`                     | `AuthController#fetchRefreshToken`            |
| `POST /auth/refresh-token/secure` (+ CSRF mw) | `AuthController#refreshToken` (calls `AuthService.verifyCsrf` first) |
| `POST /auth/logout`                           | `AuthController#logout`                       |
| `PATCH /user/:id/update`                      | `UserController#updateUser`                   |
| `GET /user/account/:id`                       | `UserController#fetchUserData`                |
| `GET /posts/search`                           | `PostController#fetchPostsBySearch`           |
| `GET /posts/:slugId`                          | `PostController#fetchPost`                    |
| `GET /posts`                                  | `PostController#fetchPosts`                   |
| `POST /posts` (multipart)                     | `PostController#createPost`                   |
| `PATCH /posts/:id` (multipart)                | `PostController#updatePost`                   |
| `DELETE /posts/:id`                           | `PostController#deletePost`                   |
| `PATCH /posts/:id/likePost`                   | `PostController#likePost`                     |
| `POST /posts/:id/commentPost`                 | `PostController#commentPost`                  |
| `POST /posts/bookmarks/add`                   | `PostController#bookmarkPost`                 |

Auth: `verifyAccessToken` middleware -> `JwtAuthFilter` (populates `request.userId` attribute,
same name/shape your controllers already expect) + Spring Security route rules in
`SecurityConfig` that match the original router's public/protected split exactly.

## Things I changed on purpose (flag if you want the old behavior instead)

- **`CACHE_EXPIRATION` is now actually used.** Your controllers read `process.env.CACHE_EXPIRY`,
  which was never set anywhere - so caching always silently used the hardcoded 300s fallback,
  regardless of what `CACHE_EXPIRATION` in `.env` said. `application.yml` now wires
  `CACHE_EXPIRATION` to the real cache TTL.
- **`REDIS_PASSWORD` is now actually sent to Redis.** `config/redis.js` never passed a
  `password` field into the `ioredis` client despite `.env` defining one - so it was dead.
  Spring Data Redis's autoconfiguration does use `spring.data.redis.password`. If your Redis
  instance doesn't actually require auth, leave it blank; if it does and this was silently
  failing auth before, this fixes it for free.
- **`JWT_SECRET_KEY` was dropped.** It's defined in `.env` but never referenced anywhere in
  the codebase (only `ACCESS_TOKEN_SECRET` / `REFRESH_TOKEN_SECRET` are used) - looked like
  leftover/dead config, so it's not in `application.yml`. Say the word if it's actually used
  somewhere I didn't see and I'll wire it back in.
- **No disk-backed multer upload step.** `middleware/upload.middleware.js` wrote the file to
  `uploads/` before `createPost`/`updatePost` streamed it to Cloudinary and unlinked it.
  `CloudinaryService.upload()` reads the `MultipartFile` bytes directly in memory and never
  touches disk - same net effect, one less moving part, no `uploads/` folder to manage.
- **User model dropped the redundant `id: String` field.** The Mongoose schema had both the
  real `_id` and a separate unused `id` string field. Spring's `@Id` already covers the real
  one; nothing in the codebase read the extra field, so it's gone. Shout if I'm wrong about
  it being unused.
- **`redis.keys('posts:*')` kept as-is** in `RedisCacheService.deleteByPattern`, matching the
  original exactly. Worth knowing `KEYS` blocks the Redis event loop on large keyspaces -
  `SCAN` is the production-safe alternative - but I didn't silently swap it since that's a
  behavior change beyond a straight port.

## Not ported (infra, not app logic)

- `Dockerfile` / `.dockerignore` - happy to write a Spring Boot equivalent if you want one.
- Dev-mode HTTPS via local certs - see the TLS note above.
- `helmet()` - Spring Boot's default security headers cover most of the same ground; add
  `spring-boot-starter-web`'s `HeadersWriter` config if you need something specific from it.
