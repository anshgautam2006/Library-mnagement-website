CREATE TABLE IF NOT EXISTS books (
    id VARCHAR(50) PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    author VARCHAR(300) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    language VARCHAR(100) NOT NULL DEFAULT 'English',
    year INT NOT NULL,
    copies INT NOT NULL,
    CONSTRAINT chk_books_copies CHECK (copies >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS users (
    email VARCHAR(320) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    approved TINYINT(1) NOT NULL DEFAULT 1,
    CONSTRAINT chk_users_role CHECK (
        role IN (
            'admin',
            'librarian',
            'member'
        )
    ),
    KEY idx_users_role_approved (role, approved)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS requests (
    email VARCHAR(320) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    KEY idx_requests_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS loans (
    id VARCHAR(50) PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    book_id VARCHAR(50) NOT NULL,
    issued VARCHAR(10) NOT NULL,
    due VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL,
    fine INT NOT NULL DEFAULT 0,
    charged TINYINT(1) NOT NULL DEFAULT 0,
    fine_paid INT NOT NULL DEFAULT 0,
    fine_charged_amount INT NOT NULL DEFAULT 0,
    fine_cancelled_amount INT NOT NULL DEFAULT 0,
    CONSTRAINT chk_loans_status CHECK (
        status IN ('issued', 'returned')
    ),
    CONSTRAINT chk_loans_fine CHECK (fine >= 0),
    CONSTRAINT chk_loans_fine_paid CHECK (fine_paid >= 0),
    CONSTRAINT chk_loans_fine_charged_amount CHECK (fine_charged_amount >= 0),
    CONSTRAINT chk_loans_fine_cancelled_amount CHECK (fine_cancelled_amount >= 0),
    KEY idx_loans_book_status (book_id, status),
    KEY idx_loans_email (email),
    KEY idx_loans_due_status (due, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS return_requests (
    id VARCHAR(50) PRIMARY KEY,
    loan_id VARCHAR(50) NOT NULL,
    member_email VARCHAR(320) NOT NULL,
    requested_at VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    reviewed_by VARCHAR(320),
    reviewed_at VARCHAR(40),
    CONSTRAINT chk_return_requests_status CHECK (
        status IN (
            'pending',
            'approved',
            'rejected'
        )
    ),
    KEY idx_return_requests_pending (status, requested_at),
    KEY idx_return_requests_loan (loan_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS complaints (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(320) NOT NULL,
    message TEXT NOT NULL,
    date VARCHAR(32) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'open',
    CONSTRAINT chk_complaints_status CHECK (
        status IN ('open', 'resolved')
    ),
    KEY idx_complaints_status_date (status, date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS announcements (
    id VARCHAR(50) PRIMARY KEY,
    date VARCHAR(50) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS events (
    id VARCHAR(50) PRIMARY KEY,
    date VARCHAR(50) NOT NULL,
    title VARCHAR(500) NOT NULL,
    time VARCHAR(80) NOT NULL,
    location VARCHAR(500) NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS app_migrations (
    name VARCHAR(100) PRIMARY KEY,
    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
