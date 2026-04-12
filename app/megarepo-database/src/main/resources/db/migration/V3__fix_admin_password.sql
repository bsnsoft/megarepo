-- Fix admin password hash (admin123 with proper BCrypt encoding)
UPDATE users SET password_hash = '$2a$10$PA.MVRspi1fFbheoNZtGOOTeOY9Q2LiaHZKbkWRORPF5wvvpKHhQS'
WHERE user_id = 'admin';

UPDATE users SET password_hash = '$2a$10$PA.MVRspi1fFbheoNZtGOOTeOY9Q2LiaHZKbkWRORPF5wvvpKHhQS'
WHERE user_id = 'anonymous';
