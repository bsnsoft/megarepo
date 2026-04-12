const API_BASE = '/api/v1';

export interface ApiErrorBody {
  status: number;
  error: string;
  message: string;
  timestamp: string;
  fieldErrors?: Record<string, string[]>;
}

export class ApiError extends Error {
  public readonly body: ApiErrorBody | null;

  constructor(
    public readonly status: number,
    message: string,
    body: ApiErrorBody | null = null,
  ) {
    super(message);
    this.name = 'ApiError';
    this.body = body;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }

  get isForbidden(): boolean {
    return this.status === 403;
  }

  get isConflict(): boolean {
    return this.status === 409;
  }

  get isValidationError(): boolean {
    return this.status === 400;
  }

  get fieldErrors(): Record<string, string[]> | undefined {
    return this.body?.fieldErrors ?? undefined;
  }
}

export class NetworkError extends Error {
  constructor(message: string = 'Connection lost. Please check your network and try again.') {
    super(message);
    this.name = 'NetworkError';
  }
}

function userFriendlyMessage(status: number, body: ApiErrorBody | null): string {
  if (body?.message) {
    return body.message;
  }
  switch (status) {
    case 400:
      return 'Invalid request. Please check your input.';
    case 401:
      return 'Session expired. Please log in again.';
    case 403:
      return 'You don\'t have permission to perform this action.';
    case 404:
      return 'The requested resource was not found.';
    case 409:
      return 'A conflict occurred. The resource may already exist.';
    case 500:
      return 'An unexpected server error occurred. Please try again later.';
    default:
      return `Request failed (HTTP ${status})`;
  }
}

class ApiClient {
  private token: string | null = localStorage.getItem('token');

  setToken(token: string | null): void {
    this.token = token;
    if (token) {
      localStorage.setItem('token', token);
    } else {
      localStorage.removeItem('token');
    }
  }

  getToken(): string | null {
    return this.token;
  }

  private headers(extra?: Record<string, string>): Record<string, string> {
    const h: Record<string, string> = {
      'Content-Type': 'application/json',
      ...extra,
    };
    if (this.token) {
      h['Authorization'] = `Bearer ${this.token}`;
    }
    return h;
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const url = `${API_BASE}${path}`;

    let res: Response;
    try {
      res = await fetch(url, {
        method,
        headers: this.headers(),
        body: body != null ? JSON.stringify(body) : undefined,
      });
    } catch {
      throw new NetworkError();
    }

    if (res.status === 401) {
      this.setToken(null);
      window.location.href = '/login';
      throw new ApiError(401, 'Session expired');
    }

    if (!res.ok) {
      let errorBody: ApiErrorBody | null = null;
      try {
        errorBody = (await res.json()) as ApiErrorBody;
      } catch {
        // Response body is not JSON
      }
      const message = userFriendlyMessage(res.status, errorBody);
      throw new ApiError(res.status, message, errorBody);
    }

    if (res.status === 204) {
      return undefined as T;
    }

    return res.json() as Promise<T>;
  }

  async get<T>(path: string): Promise<T> {
    return this.request<T>('GET', path);
  }

  async post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('POST', path, body);
  }

  async put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('PUT', path, body);
  }

  async delete(path: string): Promise<void> {
    return this.request<void>('DELETE', path);
  }

  async postText<T>(path: string, text: string, contentType: string = 'text/plain'): Promise<T> {
    const url = `${API_BASE}${path}`;

    let res: Response;
    try {
      const headers: Record<string, string> = { 'Content-Type': contentType };
      if (this.token) {
        headers['Authorization'] = `Bearer ${this.token}`;
      }
      res = await fetch(url, {
        method: 'POST',
        headers,
        body: text,
      });
    } catch {
      throw new NetworkError();
    }

    if (res.status === 401) {
      this.setToken(null);
      window.location.href = '/login';
      throw new ApiError(401, 'Session expired');
    }

    if (!res.ok) {
      let errorBody: ApiErrorBody | null = null;
      try {
        errorBody = (await res.json()) as ApiErrorBody;
      } catch {
        // Response body is not JSON
      }
      const message = userFriendlyMessage(res.status, errorBody);
      throw new ApiError(res.status, message, errorBody);
    }

    if (res.status === 204) {
      return undefined as T;
    }

    return res.json() as Promise<T>;
  }
}

export const api = new ApiClient();
