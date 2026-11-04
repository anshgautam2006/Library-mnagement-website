
import com.sun.net.httpserver.*;
import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class LibraryServer {

    static final Path ROOT = Path.of(System.getProperty("library.root", ".")).toAbsolutePath().normalize();
    static final String BOOKS = "books", USERS = "users", REQUESTS = "requests", LOANS = "loans", COMPLAINTS = "complaints";
    static final String DB_HOST = System.getenv().getOrDefault("LMS_DB_HOST", "127.0.0.1");
    static final String DB_PORT = System.getenv().getOrDefault("LMS_DB_PORT", "3306");
    static final String DB_NAME = System.getenv().getOrDefault("LMS_DB_NAME", "library_management");
    static final String DB_USER = System.getenv().getOrDefault("LMS_DB_USER", "root");
    static final String DB_PASSWORD = System.getenv().getOrDefault("LMS_DB_PASSWORD", "");
    static final String MYSQL_OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8";
    static final String JDBC_URL = "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME + MYSQL_OPTIONS;
    static final int PORT_START = Integer.parseInt(System.getProperty("port", "8081"));
    static final Map<String, User> sessions = new ConcurrentHashMap<>();
    static final String SALT = "bbau-demo-library-v2";

    record User(String email, String name, String role, String hash, boolean approved) {}

    record Book(String id, String title, String author, String subject, String language, int year, int copies) {}

    record Loan(String id, String email, String bookId, String issued, String due, String status, int fine, boolean charged) {}
    static final String[][] SEED_BOOKS = {
        {"B001", "The Discovery of India", "Jawaharlal Nehru", "History", "English", "1946", "3"},
        {"B002", "Wings of Fire", "A. P. J. Abdul Kalam", "Biography", "English", "1999", "5"},
        {"B003", "Annihilation of Caste", "B. R. Ambedkar", "Social Sciences", "English", "1936", "2"},
        {"B004", "Introduction to Algorithms", "Thomas H. Cormen", "Computer Science", "English", "2009", "4"},
        {"B005", "Godan", "Munshi Premchand", "Fiction", "Hindi", "1936", "2"},
        {"B006", "The Structure of Scientific Revolutions", "Thomas S. Kuhn", "Science", "English", "1962", "1"},
        {"B007", "Research Methodology", "C. R. Kothari", "Research", "English", "2004", "6"},
        {"B008", "India After Gandhi", "Ramachandra Guha", "History", "English", "2007", "3"}
    };
    static final List<Map<String, Object>> ANNOUNCEMENTS = List.of(item("date", "28 Oct", "title", "New e-Books Collection Added", "description", "Access new e-books across different disciplines."), item("date", "22 Oct", "title", "Library Timings Update", "description", "Library will remain open till 8:00 PM from next week."), item("date", "15 Oct", "title", "User Orientation Program", "description", "Join an introduction to library services and online resources."));
    static final List<Map<String, Object>> EVENTS = List.of(item("date", "05 Nov", "title", "Research Skills Workshop", "time", "10:00 AM", "location", "Central Library Seminar Hall"), item("date", "12 Nov", "title", "Awareness Program on E-Resources", "time", "11:00 AM", "location", "Online / Library Hall"), item("date", "20 Nov", "title", "Book Exhibition", "time", "9:00 AM – 5:00 PM", "location", "Central Library"));

    public static void main(String[] args) throws Exception {
        try {
            setupDatabase();
        } catch (Exception e) {
            System.err.println("Library server could not start: " + e.getMessage());
            System.exit(1);
            return;
        }
        HttpServer s = null;
        int port = PORT_START;
        for (; port < PORT_START + 100; port++) {
            try {
                s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
                break;
            } catch (IOException busy) {
                System.out.println("Port " + port + " is busy; trying the next port.");
            }
        }
        if (s == null) {
            throw new IOException("No free port between " + PORT_START + " and " + (PORT_START + 99) + ". Close another server and try again.");
        }
        s.setExecutor(Executors.newCachedThreadPool());
        s.createContext("/api/", LibraryServer::api);
        s.createContext("/", LibraryServer::staticFile);
        s.start();
        String home = "http://localhost:" + port + "/index.html";
        System.out.println("BBAU Central Library running at " + home);
        System.out.println("MySQL database: " + DB_HOST + ":" + DB_PORT + "/" + DB_NAME);
        System.out.println("Leave this window open while using the site.");
        if (Boolean.getBoolean("library.openBrowser")) {
            openHome(home);
        }
    }

    static void openHome(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                System.out.println("Open this address in your browser: " + url);
            }
        } catch (Exception e) {
            System.out.println("Could not open the browser automatically. Open this address: " + url);
        }
    }

    static void setupDatabase() throws Exception {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            ensureDatabase();
            initDatabase();
            ensureUsersApprovalColumn();
            ensureLoansFinePaidColumn();
            migrateMysqlSnapshot();
            seed();
        } catch (SQLException e) {
            if (connectionRefused(e)) {
                throw new IOException("No MySQL Server is accepting connections at " + DB_HOST + ":" + DB_PORT + ". Start the MySQL service or set LMS_DB_HOST and LMS_DB_PORT to its address.");
            }
            if (e.getErrorCode() == 1045) {
                throw new IOException("MySQL authentication failed for " + DB_USER + " at " + DB_HOST + ":" + DB_PORT + ". Check LMS_DB_USER and LMS_DB_PASSWORD.");
            }
            throw new IOException("MySQL database setup failed for " + DB_NAME + " at " + DB_HOST + ":" + DB_PORT + ". Details: " + e.getMessage());
        }
    }

    static boolean connectionRefused(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && (message.toLowerCase(Locale.ROOT).contains("connection refused") || message.toLowerCase(Locale.ROOT).contains("communications link failure"))) {
                return true;
            }
        }
        return false;
    }

    static void ensureDatabase() throws SQLException {
        if (!DB_NAME.matches("[A-Za-z0-9_]+")) {
            throw new SQLException("LMS_DB_NAME may contain only letters, numbers, and underscores.");

        }
        try (Connection ignored = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD)) {
            return;
        } catch (SQLException missing) {
            if (missing.getErrorCode() != 1049) {
                throw missing;

            }
        }
        String serverUrl = "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + MYSQL_OPTIONS;
        try (Connection c = DriverManager.getConnection(serverUrl, DB_USER, DB_PASSWORD); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + DB_NAME + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try (Connection ignored = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD)) {
        }
    }

    static Connection db() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD);
    }

    static void initDatabase() throws Exception {
        Path schema = ROOT.resolve("server/mysql-schema.sql");
        if (!Files.isRegularFile(schema)) {
            throw new FileNotFoundException("Missing MySQL schema file: " + schema);

        }
        String source = Files.readString(schema, StandardCharsets.UTF_8);
        try (Connection c = db(); Statement s = c.createStatement()) {
            for (String sql : source.split(";")) {
                String statement = sql.trim();
                if (!statement.isEmpty()) {
                    s.executeUpdate(statement);

                }
            }
        }
    }

    static void ensureUsersApprovalColumn() throws SQLException {
        boolean hasColumn = false;
        try (Connection c = db(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW COLUMNS FROM users LIKE 'approved'")) {
            hasColumn = r.next();
        }
        if (!hasColumn)try (Connection c = db(); Statement s = c.createStatement()) {
            s.executeUpdate("ALTER TABLE users ADD COLUMN approved TINYINT(1) NOT NULL DEFAULT 1");
        }
    }

    static void ensureLoansFinePaidColumn() throws SQLException {
        boolean hasColumn = false;
        try (Connection c = db(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW COLUMNS FROM loans LIKE 'fine_paid'")) {
            hasColumn = r.next();
        }
        if (!hasColumn) try (Connection c = db(); Statement s = c.createStatement()) {
            s.executeUpdate("ALTER TABLE loans ADD COLUMN fine_paid INT NOT NULL DEFAULT 0");
        }
        boolean hasChargedAmount = false;
        try (Connection c = db(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW COLUMNS FROM loans LIKE 'fine_charged_amount'")) {
            hasChargedAmount = r.next();
        }
        if (!hasChargedAmount) try (Connection c = db(); Statement s = c.createStatement()) {
            s.executeUpdate("ALTER TABLE loans ADD COLUMN fine_charged_amount INT NOT NULL DEFAULT 0");
        }
        boolean hasCancelledAmount = false;
        try (Connection c = db(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW COLUMNS FROM loans LIKE 'fine_cancelled_amount'")) {
            hasCancelledAmount = r.next();
        }
        if (!hasCancelledAmount) try (Connection c = db(); Statement s = c.createStatement()) {
            s.executeUpdate("ALTER TABLE loans ADD COLUMN fine_cancelled_amount INT NOT NULL DEFAULT 0");
        }
        try (Connection c = db(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE loans SET fine_charged_amount=fine WHERE charged=1 AND fine_charged_amount=0 AND fine>0");
        }
    }

    static void migrateMysqlSnapshot() throws Exception {
        String migrationName = "legacy_files_to_mysql_v1";
        Map<String, String> tables = new LinkedHashMap<>();
        tables.put("books", "id,title,author,subject,language,year,copies");
        tables.put("users", "email,name,role,password_hash,approved");
        tables.put("requests", "email,name,password_hash,created_at");
        tables.put("loans", "id,email,book_id,issued,due,status,fine,charged");
        tables.put("complaints", "id,name,email,message,date,status");
        tables.put("announcements", "id,date,title,description");
        tables.put("events", "id,date,title,time,location");
        tables.put("return_requests", "id,loan_id,member_email,requested_at,status,reviewed_by,reviewed_at");

        try (Connection c = db()) {
            try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM app_migrations WHERE name=?")) {
                p.setString(1, migrationName);
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) {
                        return;
                    }
                }
            }
            c.setAutoCommit(false);
            try {
                for (var entry : tables.entrySet()) {
                    String table = entry.getKey(), cols = entry.getValue();
                    Path source = ROOT.resolve("server/mysql-import/" + table + ".rows");
                    if (!Files.isRegularFile(source)) {
                        throw new FileNotFoundException("Missing migration data file: " + source);
                    }
                    List<String> encodedRows = Files.readAllLines(source, StandardCharsets.UTF_8);
                    int size = cols.split(",").length;
                    String marks = String.join(",", Collections.nCopies(size, "?"));
                    String key = cols.substring(0, cols.indexOf(','));
                    String sql = "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ") ON DUPLICATE KEY UPDATE " + key + "=" + key;
                    try (PreparedStatement write = c.prepareStatement(sql)) {
                        int imported = 0;
                        for (String line : encodedRows) {
                            String[] row = decRow(line);
                            if (row.length != size) {
                                throw new IOException("Invalid row in migration file: " + source);
                            }
                            bindMigration(write, table, row);
                            write.addBatch();
                            imported++;
                        }
                        if (imported > 0) {
                            write.executeBatch();
                        }
                        System.out.println("Loaded " + imported + " legacy " + table + " records into MySQL.");
                    }
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO app_migrations(name) VALUES(?)")) {
                    p.setString(1, migrationName);
                    p.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    static void bindMigration(PreparedStatement s, String table, String[] row) throws SQLException {
        for (int i = 0; i < row.length; i++) {
            if (table.equals("books") && (i == 5 || i == 6) || table.equals("loans") && i == 6) {
                s.setInt(i + 1, Integer.parseInt(row[i]));
            } else if (table.equals("users") && i == 4 || table.equals("loans") && i == 7) {
                s.setInt(i + 1, row[i].equals("1") || Boolean.parseBoolean(row[i]) ? 1 : 0);
            } else {
                s.setString(i + 1, row[i]);
            }
        }
    }

    static void seed() throws Exception {
        if (readRows(BOOKS, 7).isEmpty()) {
            List<String[]> rows = new ArrayList<>();
            for (String[] r : SEED_BOOKS) {
                rows.add(r);

            }
            saveRows(BOOKS, rows);
        }
        if (readRows(USERS, 5).isEmpty()) {
            saveRows(USERS, List.of(new String[]{"admin@bbau.ac.in", "Library Admin", "admin", hash("Admin123!"), "true"}, new String[]{"librarian@bbau.ac.in", "Library Staff", "librarian", hash("Librarian123!"), "true"}, new String[]{"student@bbau.ac.in", "Library Student", "member", hash("Library123!"), "true"}));
        }
        if (readRows(LOANS, 8).isEmpty()) {
            saveLoans(List.of(new Loan("L001", "student@bbau.ac.in", "B002", LocalDate.now().toString(), LocalDate.now().plusDays(7).toString(), "issued", 0, false)));
        }
        if (announcementRows().isEmpty()) {
            String[][] seed = {{"28 Oct", "New e-Books Collection Added", "Access new e-books across different disciplines."}, {"22 Oct", "Library Timings Update", "Library will remain open till 8:00 PM from next week."}, {"15 Oct", "User Orientation Program", "Join an introduction to library services and online resources."}};
            for (String[] r : seed) {
                insertContent("announcements", new String[]{UUID.randomUUID().toString(), r[0], r[1], r[2]});

            }
        }
        if (eventRows().isEmpty()) {
            String[][] seed = {{"05 Nov", "Research Skills Workshop", "10:00 AM", "Central Library Seminar Hall"}, {"12 Nov", "Awareness Program on E-Resources", "11:00 AM", "Online / Library Hall"}, {"20 Nov", "Book Exhibition", "9:00 AM – 5:00 PM", "Central Library"}};
            for (String[] r : seed) {
                insertContent("events", new String[]{UUID.randomUUID().toString(), r[0], r[1], r[2], r[3]});

            }
        }
    }

    static void api(HttpExchange x) throws IOException {
        headers(x);
        localCors(x);
        String p = x.getRequestURI().getPath(), m = x.getRequestMethod();
        if (m.equals("OPTIONS")) {
            x.sendResponseHeaders(204, -1);
            x.close();
            return;
        }
        try {
            if (m.equals("GET") && p.equals("/api/health")) {
                send(x, 200, json(item("service", "bbaulms-library", "apiVersion", 7)));
                return;
            }
            if (m.equals("GET") && p.equals("/api/books")) {
                String q = query(x, "q").toLowerCase(Locale.ROOT), type = query(x, "type").toLowerCase(Locale.ROOT);
                List<Map<String, Object>> found = new ArrayList<>();
                for (Book b : books()) {
                    String hay = (b.title() + " " + b.author() + " " + b.subject() + " " + b.language()).toLowerCase(Locale.ROOT);
                    if ((q.isBlank() || hay.contains(q)) && (type.isBlank() || type.startsWith("all") || type.equals("books") || type.equals("e-books") || type.equals("journals") && b.subject().equalsIgnoreCase("Research"))) {
                        int available = Math.max(0, b.copies() - activeCount(b.id()));
                        found.add(item("id", b.id(), "title", b.title(), "author", b.author(), "subject", b.subject(), "language", b.language(), "year", b.year(), "copies", b.copies(), "availableCopies", available));
                    }
                }
                send(x, 200, json(item("items", found, "count", found.size())));
                return;
            }
            if (m.equals("GET") && p.equals("/api/announcements")) {
                send(x, 200, json(item("items", announcements())));
                return;
            }
            if (m.equals("GET") && p.equals("/api/events")) {
                send(x, 200, json(item("items", events())));
                return;
            }
            if (m.equals("POST") && p.equals("/api/signup")) {
                Map<String, String> b = body(x);
                String email = b.getOrDefault("email", "").trim().toLowerCase(Locale.ROOT), name = b.getOrDefault("name", "").trim(), pw = b.getOrDefault("password", "");
                if (name.isBlank() || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || pw.length() < 8) {
                    send(x, 400, json(item("error", "Enter a valid email, name, and password of at least 8 characters.")));
                    return;
                }
                if (findUser(email) != null || requestExists(email)) {
                    send(x, 409, json(item("error", "This account or request already exists.")));
                    return;
                }
                append(REQUESTS, new String[]{email, name, hash(pw), LocalDate.now().toString()});
                send(x, 201, json(item("ok", true, "message", "Your membership request was sent to the administrator for approval.")));
                return;
            }
            if (m.equals("POST") && p.equals("/api/login")) {
                Map<String, String> b = body(x);
                String email = b.getOrDefault("email", "").trim().toLowerCase(Locale.ROOT), pw = b.getOrDefault("password", ""), requestedRole = b.getOrDefault("role", "").trim().toLowerCase(Locale.ROOT);
                User u = findUser(email);
                if (u == null || !u.approved() || !u.role().equals(requestedRole) || !MessageDigest.isEqual(hash(pw).getBytes(StandardCharsets.UTF_8), u.hash().getBytes(StandardCharsets.UTF_8))) {
                    send(x, 401, json(item("error", "Sign-in failed. Check your details or wait for membership approval.")));
                    return;
                }
                String token = UUID.randomUUID().toString();
                sessions.put(token, u);
                x.getResponseHeaders().add("Set-Cookie", "lms_session=" + token + "; Path=/; HttpOnly; SameSite=Lax");
                send(x, 200, json(item("user", userView(u))));
                return;
            }
            if (m.equals("POST") && p.equals("/api/logout")) {
                String t = cookie(x);
                if (t != null) {
                    sessions.remove(t);

                }
                x.getResponseHeaders().add("Set-Cookie", "lms_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
                send(x, 200, json(item("ok", true)));
                return;
            }
            if (m.equals("GET") && p.equals("/api/account")) {
                User u = auth(x);
                if (u == null) {
                    unauthorized(x);
                    return;
                }
                send(x, 200, json(account(u)));
                return;
            }
            if (m.equals("GET") && p.equals("/api/admin/data")) {
                User u = auth(x);
                if (!role(x, u, "admin")) {
                    return;

                }
                send(x, 200, json(item("users", users().stream().map(LibraryServer::userView).toList(), "requests", requests().stream().map(r -> item("email", r[0], "name", r[1], "date", r[3])).toList(), "complaints", complaints(), "loans", allLoans(), "returnRequests", pendingReturns(), "announcements", announcements(), "events", events())));
                return;
            }
            if (m.equals("GET") && p.equals("/api/staff/returns")) {
                User u = auth(x);
                if (!staff(x, u)) {
                    return;

                }
                send(x, 200, json(item("items", pendingReturns())));
                return;
            }
            if (m.equals("GET") && p.equals("/api/staff/loans")) {
                User u = auth(x);
                if (!staff(x, u)) {
                    return;

                }
                send(x, 200, json(item("items", allLoans())));
                return;
            }
            if (m.equals("POST") && p.equals("/api/staff/fines/paid")) {
                User u = auth(x);
                if (!staff(x, u)) {
                    return;
                }
                String loanId = body(x).getOrDefault("loanId", "");
                boolean paid = markFinePaid(loanId);
                if (!paid) {
                    send(x, 400, json(item("error", "No unpaid fine found for this loan.")));
                    return;
                }
                send(x, 200, json(item("ok", true, "message", "Overdue fine marked as paid.")));
                return;
            }
            if (m.equals("POST") && p.equals("/api/staff/fines/cancel")) {
                User u = auth(x);
                if (!staff(x, u)) {
                    return;
                }
                String loanId = body(x).getOrDefault("loanId", "");
                if (!cancelFine(loanId)) {
                    send(x, 400, json(item("error", "No unpaid fine found to cancel for this loan.")));
                    return;
                }
                send(x, 200, json(item("ok", true, "message", "Outstanding fine canceled.")));
                return;
            }
            if (m.equals("POST") && p.startsWith("/api/staff/returns/")) {
                User u = auth(x);
                if (!staff(x, u)) {
                    return;

                }
                String id = URLDecoder.decode(p.substring("/api/staff/returns/".length()), StandardCharsets.UTF_8), action = body(x).getOrDefault("action", "");
                if (!Set.of("approve", "reject").contains(action)) {
                    send(x, 400, json(item("error", "Choose approve or reject.")));
                    return;
                }
                String review = reviewReturn(id, action, u.email());
                if (review.equals("unpaid_fine")) {
                    send(x, 409, json(item("error", "The return cannot be approved until the overdue fine is marked as paid.")));
                    return;
                }
                if (!review.equals("ok")) {
                    send(x, 404, json(item("error", "Pending return request not found.")));
                    return;
                }
                send(x, 200, json(item("ok", true, "message", action.equals("approve") ? "Return approved; the resource is now available." : "Return request rejected.")));
                return;
            }
            if (m.equals("POST") && p.equals("/api/admin/announcements")) {
                User u = auth(x);
                if (!role(x, u, "admin")) {
                    return;

                }
                Map<String, String> b = body(x);
                String date = b.getOrDefault("date", "").trim(), title = b.getOrDefault("title", "").trim(), description = b.getOrDefault("description", "").trim();
                if (date.isBlank() || title.isBlank() || description.isBlank()) {
                    send(x, 400, json(item("error", "Enter the date, title, and announcement.")));
                    return;
                }
                String id = UUID.randomUUID().toString();
                insertContent("announcements", new String[]{id, date, title, description});
                send(x, 201, json(item("ok", true, "message", "Homepage announcement added.")));
                return;
            }
            if (m.equals("PUT") && p.startsWith("/api/admin/announcements/")) {
                User u = auth(x);
                if (!role(x, u, "admin")) {
                    return;

                }
                String id = URLDecoder.decode(p.substring("/api/admin/announcements/".length()), StandardCharsets.UTF_8);
                Map<String, String> b = body(x);
                String date = b.getOrDefault("date", "").trim(), title = b.getOrDefault("title", "").trim(), description = b.getOrDefault("description", "").trim();
                if (date.isBlank() || title.isBlank() || description.isBlank()) {
                    send(x, 400, json(item("error", "Enter the date, title, and announcement.")));
                    return;
                }
                boolean changed = updateContent("announcements", id, new String[]{date, title, description});
                send(x, changed ? 200 : 404, json(item("ok", changed, "message", changed ? "Homepage announcement updated." : "Announcement not found.")));
                return;
            }
            if (m.equals("DELETE") && p.startsWith("/api/admin/announcements/")) {
                User u = auth(x);
                if (!role(x, u, "admin")) {
                    return;

                }
                boolean removed = deleteContent("announcements", URLDecoder.decode(p.substring("/api/admin/announcements/".length()), StandardCharsets.UTF_8));
                send(x, removed ? 200 : 404, json(item("ok", removed, "error", removed ? "" : "Announcement not found.")));
                return;
            }
            if (m.equals("POST") && p.equals("/api/admin/events")) {
                User u = auth(x);
                if (!role(x, u, "admin")) {
                    return;

                }
                Map<String, String> b = body(x);
                String date = b.getOrDefault("date", "").trim(), title = b.getOrDefault("title", "").trim(), time = b.getOrDefault("time", "").trim(), location = b.getOrDefault("location", "").trim();
                if (date.isBlank() || title.isBlank() || time.isBlank() || location.isBlank()) {
                    send(x, 400, json(item("error", "Complete all event fields.")));
                    return;
                }
                String id = UUID.randomUUID().toString();
                insertContent("events", new String[]{id, date, title, time, location});
                send(x, 201, json(item("ok", true, "message", "Homepage event added.")));
                return;
            }
            if (m.equals("PUT") && p.startsWith("/api/admin/events/")) {
                User u = auth(x);
                if (!role(x, u, "admin")) {
                    return;

                }
                String id = URLDecoder.decode(p.substring("/api/admin/events/".length()), StandardCharsets.UTF_8);
                Map<String, String> b = body(x);
                String date = b.getOrDefault("date", "").trim(), title = b.getOrDefault("title", "").trim(), time = b.getOrDefault("time", "").trim(), location = b.getOrDefault("location", "").trim();
                if (date.isBlank() || title.isBlank() || time.isBlank() || location.isBlank()) {
                    send(x, 400, json(item("error", "Complete all event fields.")));
                    return;
                }
                boolean changed = updateContent("events", id, new String[]{date, title, time, location});
                send(x, changed ? 200 : 404, json(item("ok", changed, "message", changed ? "Homepage event updated." : "Event not found.")));
                return;
            }
            if (m.equals("DELETE") && p.startsWith("/api/admin/events/")) {
                User u = auth(x);
                if (!role(x, u, "admin")) {
                    return;

                }
                boolean removed = deleteContent("events", URLDecoder.decode(p.substring("/api/admin/events/".length()), StandardCharsets.UTF_8));
                send(x, removed ? 200 : 404, json(item("ok", removed, "error", removed ? "" : "Event not found.")));
                return;
            }
            if (m.equals("POST") && p.startsWith("/api/admin/complaints/")) {
                User u = auth(x);
                if (!role(x, u, "admin")) {
                    return;

                }
                String id = URLDecoder.decode(p.substring("/api/admin/complaints/".length()), StandardCharsets.UTF_8), status = body(x).getOrDefault("status", "");
                if (!Set.of("open", "resolved").contains(status)) {
                    send(x, 400, json(item("error", "Choose open or resolved.")));
                    return;
                }
                try (Connection c = db(); PreparedStatement s = c.prepareStatement("UPDATE complaints SET status=? WHERE id=?")) {
                    s.setString(1, status);
                    s.setString(2, id);
                    int changed = s.executeUpdate();
                    send(x, changed == 0 ? 404 : 200, json(item("ok", changed > 0)));
                } catch (SQLException e) {
                    throw new IOException("Could not update complaint.", e);
                }
                return;
            }
            if (m.equals("POST") && p.equals("/api/admin/requests")) {
                User u = auth(x);
                if (!role(x, u, "admin")) {
                    return;

                }
                Map<String, String> b = body(x);
                String email = b.getOrDefault("email", "").toLowerCase(Locale.ROOT), action = b.getOrDefault("action", "");
                List<String[]> req = requests();
                String[] found = req.stream().filter(r -> r[0].equals(email)).findFirst().orElse(null);
                if (found == null) {
                    send(x, 404, json(item("error", "Request not found.")));
                    return;
                }
                req.remove(found);
                saveRows(REQUESTS, req);
                if (action.equals("approve")) {
                    List<String[]> usr = userRows();
                    usr.add(new String[]{found[0], found[1], "member", found[2], "true"});
                    saveRawRows(USERS, usr);
                    send(x, 200, json(item("ok", true, "message", "Member approved.")));
                } else {
                    send(x, 200, json(item("ok", true, "message", "Request rejected.")));

                }
                return;
            }
            if (m.equals("POST") && p.equals("/api/admin/members")) {
                User u = auth(x);
                if (!role(x, u, "admin")) {
                    return;

                }
                Map<String, String> b = body(x);
                String email = b.getOrDefault("email", "").trim().toLowerCase(Locale.ROOT), name = b.getOrDefault("name", "").trim(), pw = b.getOrDefault("password", "");
                if (name.isBlank() || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || pw.length() < 8) {
                    send(x, 400, json(item("error", "Enter a valid email, name, and password of at least 8 characters.")));
                    return;
                }
                if (findUser(email) != null) {
                    send(x, 409, json(item("error", "A user with this email already exists.")));
                    return;
                }
                append(USERS, new String[]{email, name, "member", hash(pw), "true"});
                send(x, 201, json(item("ok", true, "message", "Member added.")));
                return;
            }
            if (m.equals("DELETE") && p.startsWith("/api/admin/members/")) {
                User u = auth(x);
                if (!role(x, u, "admin")) {
                    return;

                }
                String email = URLDecoder.decode(p.substring("/api/admin/members/".length()), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (email.equals(u.email())) {
                    send(x, 400, json(item("error", "You cannot remove your own admin account.")));
                    return;
                }
                List<String[]> rows = userRows();
                boolean removed = rows.removeIf(r -> r[0].equals(email) && r[2].equals("member"));
                saveRawRows(USERS, rows);
                if (!removed) {
                    send(x, 404, json(item("error", "Member not found.")));
                    return;
                }
                send(x, 200, json(item("ok", true)));
                return;
            }
            if (m.equals("POST") && (p.equals("/api/admin/fines") || p.equals("/api/staff/fines"))) {
                User u = auth(x);
                if (!staff(x, u)) {
                    return;

                }
                Map<String, String> request = body(x);
                String id = request.getOrDefault("loanId", "");
                boolean manual = request.containsKey("amount");
                int manualAmount = 0;
                if (manual) {
                    try {
                        manualAmount = Integer.parseInt(request.getOrDefault("amount", ""));
                    } catch (NumberFormatException invalidAmount) {
                        send(x, 400, json(item("error", "Enter a whole manual fine amount between 1 and 500000.")));
                        return;
                    }
                    if (manualAmount < 1 || manualAmount > 500000) {
                        send(x, 400, json(item("error", "Enter a whole manual fine amount between 1 and 500000.")));
                        return;
                    }
                }
                List<Loan> ls = loans();
                boolean done = false;
                int chargedAmount = 0;
                for (int i = 0; i < ls.size(); i++) {
                    Loan l = ls.get(i);
                    if (l.id().equals(id)) {
                        int fine = Math.max(l.fine(), fineFor(l));
                        chargedAmount = fine;
                        if (manual) {
                            fine = Math.addExact(fine, manualAmount);
                            chargedAmount = fine;
                        } else if (l.charged() || fine <= 0) {
                            break;
                        }
                        ls.set(i, new Loan(l.id(), l.email(), l.bookId(), l.issued(), l.due(), l.status(), fine, true));
                        done = true;
                        break;
                    }
                }
                if (!done) {
                    send(x, 400, json(item("error", manual ? "Loan not found; the manual fine was not applied." : "No uncharged overdue fine found.")));
                    return;
                }
                saveLoans(ls);
                try (Connection c = db(); PreparedStatement update = c.prepareStatement("UPDATE loans SET fine_charged_amount=? WHERE id=?")) {
                    update.setInt(1, chargedAmount);
                    update.setString(2, id);
                    update.executeUpdate();
                } catch (SQLException e) {
                    throw new IOException("Could not save the charged fine total.", e);
                }
                send(x, 200, json(item("ok", true, "message", manual ? "Manual fine added to the borrower's outstanding balance." : "Overdue fine recorded.")));
                return;
            }
            if (m.equals("POST") && p.equals("/api/books")) {
                User u = auth(x);
                if (!staff(x, u)) {
                    return;

                }
                Map<String, String> b = body(x);
                String id = b.getOrDefault("id", UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
                if (findBook(id) != null) {
                    send(x, 409, json(item("error", "Resource ID already exists.")));
                    return;
                }
                Book book = parseBook(id, b);
                if (book == null) {
                    send(x, 400, json(item("error", "Provide title, author, subject, year and a non-negative copy count.")));
                    return;
                }
                List<Book> bs = books();
                bs.add(book);
                saveBooks(bs);
                send(x, 201, json(item("ok", true, "book", bookView(book))));
                return;
            }
            if (m.equals("PUT") && p.startsWith("/api/books/")) {
                User u = auth(x);
                if (!staff(x, u)) {
                    return;

                }
                String id = URLDecoder.decode(p.substring("/api/books/".length()), StandardCharsets.UTF_8);
                Book old = findBook(id);
                if (old == null) {
                    send(x, 404, json(item("error", "Resource not found.")));
                    return;
                }
                Book changed = parseBook(id, body(x));
                if (changed == null) {
                    send(x, 400, json(item("error", "Provide valid resource details and a non-negative copy count.")));
                    return;
                }
                if (changed.copies() < activeCount(id)) {
                    send(x, 400, json(item("error", "Copy count cannot be lower than the number currently issued.")));
                    return;
                }
                List<Book> bs = books();
                bs.replaceAll(b -> b.id().equals(id) ? changed : b);
                saveBooks(bs);
                send(x, 200, json(item("ok", true, "book", bookView(changed))));
                return;
            }
            if (m.equals("DELETE") && p.startsWith("/api/books/")) {
                User u = auth(x);
                if (!staff(x, u)) {
                    return;

                }
                String id = URLDecoder.decode(p.substring("/api/books/".length()), StandardCharsets.UTF_8);
                if (activeCount(id) > 0) {
                    send(x, 409, json(item("error", "This resource has active loans and cannot be removed yet.")));
                    return;
                }
                List<Book> bs = books();
                boolean removed = bs.removeIf(b -> b.id().equals(id));
                saveBooks(bs);
                if (!removed) {
                    send(x, 404, json(item("error", "Resource not found.")));
                    return;
                }
                send(x, 200, json(item("ok", true)));
                return;
            }
            if (m.equals("POST") && p.equals("/api/loans")) {
                User u = auth(x);
                if (u == null) {
                    unauthorized(x);
                    return;
                }
                if (!u.role().equals("member")) {
                    send(x, 403, json(item("error", "Only members can issue resources.")));
                    return;
                }
                String id = body(x).getOrDefault("bookId", "");
                Book b = findBook(id);
                if (b == null) {
                    send(x, 404, json(item("error", "Resource not found.")));
                    return;
                }
                if (b.copies() - activeCount(id) <= 0) {
                    send(x, 409, json(item("error", "No copies are currently available.")));
                    return;
                }
                List<Loan> ls = loans();
                if (ls.stream().anyMatch(l -> l.email().equals(u.email()) && l.bookId().equals(id) && !l.status().equals("returned"))) {
                    send(x, 409, json(item("error", "You already have this resource issued.")));
                    return;
                }
                Loan l = new Loan("L" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT), u.email(), id, LocalDate.now().toString(), LocalDate.now().plusDays(14).toString(), "issued", 0, false);
                ls.add(l);
                saveLoans(ls);
                send(x, 201, json(item("ok", true, "message", "Resource issued successfully.", "loan", loanView(l))));
                return;
            }
            if (m.equals("POST") && p.startsWith("/api/loans/") && (p.endsWith("/return-request") || p.endsWith("/renew"))) {
                User u = auth(x);
                if (u == null) {
                    unauthorized(x);
                    return;
                }
                boolean returning = p.endsWith("/return-request");
                String suffix = returning ? "/return-request" : "/renew", id = p.substring("/api/loans/".length(), p.length() - suffix.length());
                if (returning) {
                    if (!u.role().equals("member")) {
                        send(x, 403, json(item("error", "Only members can request a return.")));
                        return;
                    }
                    Loan loan = loans().stream().filter(l -> l.id().equals(id) && l.email().equals(u.email()) && l.status().equals("issued")).findFirst().orElse(null);
                    if (loan == null) {
                        send(x, 404, json(item("error", "Active loan not found.")));
                        return;
                    }
                    if (returnPending(id)) {
                        send(x, 409, json(item("error", "A return request is already waiting for staff approval.")));
                        return;
                    }
                    createReturnRequest(id, u.email());
                    send(x, 201, json(item("ok", true, "message", "Return request sent. A librarian or administrator must approve it before the resource is checked in.", "account", account(u))));
                    return;
                }
                List<Loan> ls = loans();
                boolean changed = false;
                for (int i = 0; i < ls.size(); i++) {
                    Loan l = ls.get(i);
                    if (l.id().equals(id) && l.email().equals(u.email()) && l.status().equals("issued")) {
                        if (returnPending(id)) {
                            send(x, 409, json(item("error", "This loan has a pending return request and cannot be renewed.")));
                            return;
                        }
                        if (!LocalDate.parse(l.due()).isBefore(LocalDate.now())) {
                            ls.set(i, new Loan(l.id(), l.email(), l.bookId(), l.issued(), LocalDate.now().plusDays(14).toString(), l.status(), l.fine(), l.charged()));
                            changed = true;
                        }
                    }
                }
                if (!changed) {
                    send(x, 404, json(item("error", "Loan not found or renewal is unavailable.")));
                    return;
                }
                saveLoans(ls);
                send(x, 200, json(item("ok", true, "message", "Loan renewed for 14 days.", "account", account(u))));
                return;
            }
            if (m.equals("POST") && p.equals("/api/contact")) {
                Map<String, String> b = body(x);
                String name = b.getOrDefault("name", "").trim(), email = b.getOrDefault("email", "").trim(), msg = b.getOrDefault("message", "").trim();
                if (name.isBlank() || email.isBlank() || msg.isBlank()) {
                    send(x, 400, json(item("error", "Please complete all fields.")));
                    return;
                }
                append(COMPLAINTS, new String[]{"C" + UUID.randomUUID().toString().substring(0, 8), name, email, msg, LocalDate.now().toString(), "open"});
                send(x, 201, json(item("ok", true, "message", "Your complaint or message was sent to the administrator.")));
                return;
            }
            send(x, 404, json(item("error", "API endpoint not found.")));
        } catch (Exception e) {
            e.printStackTrace();
            send(x, 500, json(item("error", "The server could not complete this request.")));
        }
    }

    static boolean role(HttpExchange x, User u, String needed) throws IOException {
        if (u == null) {
            unauthorized(x);
            return false;
        }
        if (!u.role().equals(needed)) {
            send(x, 403, json(item("error", "This action requires " + needed + " access.")));
            return false;
        }
        return true;
    }

    static boolean staff(HttpExchange x, User u) throws IOException {
        if (u == null) {
            unauthorized(x);
            return false;
        }
        if (!u.role().equals("admin") && !u.role().equals("librarian")) {
            send(x, 403, json(item("error", "Librarian or admin access is required.")));
            return false;
        }
        return true;
    }

    static void unauthorized(HttpExchange x) throws IOException {
        send(x, 401, json(item("error", "Please sign in to continue.")));
    }

    static User auth(HttpExchange x) {
        String t = cookie(x);
        return t == null ? null : sessions.get(t);
    }

    static boolean returnPending(String loanId) throws IOException {
        try (Connection c = db(); PreparedStatement s = c.prepareStatement("SELECT 1 FROM return_requests WHERE loan_id=? AND status='pending' LIMIT 1")) {
            s.setString(1, loanId);
            try (ResultSet r = s.executeQuery()) {
                return r.next();
            }
        } catch (SQLException e) {
            throw new IOException("Could not check return request.", e);
        }
    }

    static void createReturnRequest(String loanId, String email) throws IOException {
        try (Connection c = db(); PreparedStatement s = c.prepareStatement("INSERT INTO return_requests(id,loan_id,member_email,requested_at,status) VALUES(?,?,?,?,'pending')")) {
            s.setString(1, "R" + UUID.randomUUID().toString().substring(0, 10).toUpperCase(Locale.ROOT));
            s.setString(2, loanId);
            s.setString(3, email);
            s.setString(4, LocalDateTime.now().toString());
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Could not create return request.", e);
        }
    }

    static List<Map<String, Object>> pendingReturns() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT r.id,r.loan_id,r.member_email,r.requested_at,l.issued,l.due,l.fine,l.charged,l.fine_paid,l.fine_cancelled_amount,b.title FROM return_requests r JOIN loans l ON l.id=r.loan_id LEFT JOIN books b ON b.id=l.book_id WHERE r.status='pending' AND l.status='issued' ORDER BY r.requested_at";
        try (Connection c = db(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) {
                Loan loan = new Loan(r.getString(2), r.getString(3), "", r.getString(5), r.getString(6), "issued", r.getInt(7), r.getInt(8) != 0);
                int fine = Math.max(loan.fine(), fineFor(loan));
                int paid = r.getInt(9);
                int cancelled = r.getInt(10);
                out.add(item("id", r.getString(1), "loanId", r.getString(2), "memberEmail", r.getString(3), "requestedAt", r.getString(4), "title", Objects.requireNonNullElse(r.getString(11), "Resource"), "dueDate", r.getString(6), "fine", fine, "finePaid", paid, "fineCancelled", cancelled, "fineOutstanding", Math.max(0, fine - paid - cancelled)));
            }
        } catch (SQLException e) {
            throw new IOException("Could not load return requests.", e);
        }
        return out;
    }

    static boolean markFinePaid(String loanId) throws IOException {
        try (Connection c = db()) {
            c.setAutoCommit(false);
            try {
                Loan loan;
                int paid, cancelled;
                try (PreparedStatement q = c.prepareStatement("SELECT id,email,book_id,issued,due,status,fine,charged,fine_paid,fine_cancelled_amount FROM loans WHERE id=? FOR UPDATE")) {
                    q.setString(1, loanId);
                    try (ResultSet r = q.executeQuery()) {
                        if (!r.next()) { c.rollback(); return false; }
                        loan = new Loan(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getInt(7), r.getInt(8) != 0);
                        paid = r.getInt(9);
                        cancelled = r.getInt(10);
                    }
                }
                int fine = Math.max(loan.fine(), fineFor(loan));
                int toPay = fine - cancelled;
                if (toPay <= paid) { c.rollback(); return false; }
                try (PreparedStatement u = c.prepareStatement("UPDATE loans SET fine_paid=? WHERE id=? AND fine_paid=?")) {
                    u.setInt(1, toPay);
                    u.setString(2, loanId);
                    u.setInt(3, paid);
                    if (u.executeUpdate() != 1) { c.rollback(); return false; }
                }
                c.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException("Could not mark the fine as paid.", e);
        }
    }

    static boolean cancelFine(String loanId) throws IOException {
        try (Connection c = db()) {
            c.setAutoCommit(false);
            try {
                Loan loan;
                int paid, cancelled;
                try (PreparedStatement q = c.prepareStatement("SELECT id,email,book_id,issued,due,status,fine,charged,fine_paid,fine_cancelled_amount FROM loans WHERE id=? FOR UPDATE")) {
                    q.setString(1, loanId);
                    try (ResultSet r = q.executeQuery()) {
                        if (!r.next()) { c.rollback(); return false; }
                        loan = new Loan(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getInt(7), r.getInt(8) != 0);
                        paid = r.getInt(9);
                        cancelled = r.getInt(10);
                    }
                }
                int fine = Math.max(loan.fine(), fineFor(loan));
                int waivedTotal = fine - paid;
                if (waivedTotal <= cancelled) { c.rollback(); return false; }
                try (PreparedStatement u = c.prepareStatement("UPDATE loans SET fine_cancelled_amount=? WHERE id=? AND fine_cancelled_amount=?")) {
                    u.setInt(1, waivedTotal);
                    u.setString(2, loanId);
                    u.setInt(3, cancelled);
                    if (u.executeUpdate() != 1) { c.rollback(); return false; }
                }
                c.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException("Could not cancel the fine.", e);
        }
    }

    static int fineCancelledAmount(String loanId) throws IOException {
        try (Connection c = db(); PreparedStatement s = c.prepareStatement("SELECT fine_cancelled_amount FROM loans WHERE id=?")) {
            s.setString(1, loanId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IOException("Could not load canceled fine totals.", e);
        }
    }

    static int finePaidAmount(String loanId) throws IOException {
        try (Connection c = db(); PreparedStatement s = c.prepareStatement("SELECT fine_paid FROM loans WHERE id=?")) {
            s.setString(1, loanId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IOException("Could not load fine payment status.", e);
        }
    }

    static String reviewReturn(String requestId, String action, String reviewer) throws IOException {
        try (Connection c = db()) {
            c.setAutoCommit(false);
            try {
                String loanId = null, email = null;
                try (PreparedStatement q = c.prepareStatement("SELECT loan_id,member_email FROM return_requests WHERE id=? AND status='pending'")) {
                    q.setString(1, requestId);
                    try (ResultSet r = q.executeQuery()) {
                        if (r.next()) {
                            loanId = r.getString(1);
                            email = r.getString(2);
                        }
                    }
                }
                if (loanId == null) {
                    c.rollback();
                    return "not_found";
                }
                if (action.equals("approve")) {
                    try (PreparedStatement q = c.prepareStatement("SELECT id,email,book_id,issued,due,status,fine,charged,fine_paid,fine_cancelled_amount FROM loans WHERE id=? AND email=? AND status='issued' FOR UPDATE")) {
                        q.setString(1, loanId);
                        q.setString(2, email);
                        try (ResultSet r = q.executeQuery()) {
                            if (!r.next()) {
                                c.rollback();
                                return "not_found";
                            }
                            Loan loan = new Loan(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getInt(7), r.getInt(8) != 0);
                            int fine = Math.max(loan.fine(), fineFor(loan));
                            if (fine > r.getInt(9) + r.getInt(10)) {
                                c.rollback();
                                return "unpaid_fine";
                            }
                            try (PreparedStatement u = c.prepareStatement("UPDATE loans SET status='returned',fine=? WHERE id=? AND status='issued'")) {
                                u.setInt(1, fine);
                                u.setString(2, loanId);
                                if (u.executeUpdate() != 1) {
                                    c.rollback();
                                    return "not_found";
                                }
                            }
                        }
                    }
                }
                try (PreparedStatement u = c.prepareStatement("UPDATE return_requests SET status=?,reviewed_by=?,reviewed_at=? WHERE id=? AND status='pending'")) {
                    u.setString(1, action.equals("approve") ? "approved" : "rejected");
                    u.setString(2, reviewer);
                    u.setString(3, LocalDateTime.now().toString());
                    u.setString(4, requestId);
                    if (u.executeUpdate() != 1) {
                        c.rollback();
                        return "not_found";
                    }
                }
                c.commit();
                return "ok";
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException("Could not review return request.", e);
        }
    }

    static Map<String, Object> account(User u) throws Exception {
        List<Map<String, Object>> my = new ArrayList<>();
        int balance = 0;
        for (Loan l : loans()) {
            if (l.email().equals(u.email())) {
                int fine = Math.max(l.fine(), fineFor(l));
                Map<String, Object> v = loanView(l);
                v.put("fine", fine);
                my.add(v);
                balance += Math.max(0, fine - finePaidAmount(l.id()) - fineCancelledAmount(l.id()));
            }

        }
        return item("user", userView(u), "loans", my, "fineBalance", balance);
    }

    static List<Map<String, Object>> allLoans() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, String> memberNames = new HashMap<>();
        for (User member : users()) {
            memberNames.put(member.email(), member.name());
        }
        for (Loan l : loans()) {
            Map<String, Object> v = loanView(l);
            v.put("memberName", memberNames.getOrDefault(l.email(), l.email()));
            v.put("fine", Math.max(l.fine(), fineFor(l)));
            out.add(v);
        }
        return out;
    }

    static Map<String, Object> loanView(Loan l) throws Exception {
        Book b = findBook(l.bookId());
        int fine = Math.max(l.fine(), fineFor(l));
        int paid = finePaidAmount(l.id());
        int cancelled = fineCancelledAmount(l.id());
        return item("id", l.id(), "email", l.email(), "bookId", l.bookId(), "title", b == null ? l.bookId() : b.title(), "issued", l.issued(), "dueDate", l.due(), "status", l.status(), "fine", fine, "finePaid", paid, "fineCancelled", cancelled, "fineOutstanding", Math.max(0, fine - paid - cancelled), "fineChargedAmount", fineChargedAmount(l.id()), "fineCharged", l.charged(), "returnPending", returnPending(l.id()));
    }

    static int fineChargedAmount(String loanId) throws IOException {
        try (Connection c = db(); PreparedStatement s = c.prepareStatement("SELECT fine_charged_amount FROM loans WHERE id=?")) {
            s.setString(1, loanId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IOException("Could not load charged fine totals.", e);
        }
    }

    static int fineFor(Loan l) {
        if (l.status().equals("returned")) {
            return l.fine();

        }
        long days = ChronoUnit.DAYS.between(LocalDate.parse(l.due()), LocalDate.now());
        return days > 0 ? (int) days * 10 : 0;
    }

    static int activeCount(String id) throws Exception {
        return (int) loans().stream().filter(l -> l.bookId().equals(id) && l.status().equals("issued")).count();
    }

    static List<Map<String, Object>> complaints() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] r : readRows(COMPLAINTS, 6)) {
            out.add(item("id", r[0], "name", r[1], "email", r[2], "message", r[3], "date", r[4], "status", r[5]));

        }
        return out;
    }

    static List<String[]> announcementRows() throws IOException {
        return contentRows("announcements", "id,date,title,description", 4);
    }

    static List<String[]> eventRows() throws IOException {
        return contentRows("events", "id,date,title,time,location", 5);
    }

    static List<String[]> contentRows(String table, String cols, int count) throws IOException {
        if (!Set.of("announcements", "events").contains(table)) {
            throw new IOException("Invalid content table.");

        }
        List<String[]> rows = new ArrayList<>();
        try (Connection c = db(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT " + cols + " FROM " + table + " ORDER BY id DESC")) {
            while (r.next()) {
                String[] row = new String[count];
                for (int i = 0; i < count; i++) {
                    row[i] = r.getString(i + 1);

                }
                rows.add(row);
            }
        } catch (SQLException e) {
            throw new IOException("Could not read " + table + ".", e);
        }
        return rows;
    }

    static List<Map<String, Object>> announcements() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] r : announcementRows()) {
            out.add(item("id", r[0], "date", r[1], "title", r[2], "description", r[3]));

        }
        return out;
    }

    static List<Map<String, Object>> events() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] r : eventRows()) {
            out.add(item("id", r[0], "date", r[1], "title", r[2], "time", r[3], "location", r[4]));

        }
        return out;
    }

    static void insertContent(String table, String[] row) throws IOException {
        String cols = table.equals("announcements") ? "id,date,title,description" : "id,date,title,time,location";
        if (!Set.of("announcements", "events").contains(table)) {
            throw new IOException("Invalid content table.");

        }
        String marks = String.join(",", Collections.nCopies(row.length, "?"));
        try (Connection c = db(); PreparedStatement s = c.prepareStatement("INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ")")) {
            bindStrings(s, row);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Could not save " + table + ".", e);
        }
    }

    static boolean deleteContent(String table, String id) throws IOException {
        if (!Set.of("announcements", "events").contains(table)) {
            throw new IOException("Invalid content table.");

        }
        try (Connection c = db(); PreparedStatement s = c.prepareStatement("DELETE FROM " + table + " WHERE id=?")) {
            s.setString(1, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IOException("Could not delete " + table + ".", e);
        }
    }

    static boolean updateContent(String table, String id, String[] values) throws IOException {
        if (!Set.of("announcements", "events").contains(table)) {
            throw new IOException("Invalid content table.");

        }
        String sql = table.equals("announcements") ? "UPDATE announcements SET date=?,title=?,description=? WHERE id=?" : "UPDATE events SET date=?,title=?,time=?,location=? WHERE id=?";
        try (Connection c = db(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                s.setString(i + 1, values[i]);

            }
            s.setString(values.length + 1, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IOException("Could not update " + table + ".", e);
        }
    }

    static void bindStrings(PreparedStatement s, String[] row) throws SQLException {
        for (int i = 0; i < row.length; i++) {
            s.setString(i + 1, row[i]);

        }
    }

    static List<User> users() throws IOException {
        List<User> out = new ArrayList<>();
        for (String[] r : userRows()) {
            out.add(new User(r[0].trim().toLowerCase(Locale.ROOT), r[1], r[2].trim().toLowerCase(Locale.ROOT), r[3].trim(), Boolean.parseBoolean(r[4])));

        }
        return out;
    }

    static User findUser(String email) throws IOException {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return users().stream().filter(u -> u.email().equals(normalized)).findFirst().orElse(null);
    }

    static Map<String, Object> userView(User u) {
        return item("email", u.email(), "name", u.name(), "role", u.role(), "approved", u.approved());
    }

    static boolean requestExists(String email) throws IOException {
        return requests().stream().anyMatch(r -> r[0].equals(email));
    }

    static List<String[]> requests() throws IOException {
        return readRows(REQUESTS, 4);
    }

    static List<String[]> userRows() throws IOException {
        return readRows(USERS, 5);
    }

    static List<Book> books() throws IOException {
        List<Book> out = new ArrayList<>();
        for (String[] r : readRows(BOOKS, 7)) {
            out.add(new Book(r[0], r[1], r[2], r[3], r[4], Integer.parseInt(r[5]), Integer.parseInt(r[6])));

        }
        return out;
    }

    static Book findBook(String id) throws Exception {
        return books().stream().filter(b -> b.id().equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    static Book parseBook(String id, Map<String, String> b) {
        try {
            String title = b.getOrDefault("title", "").trim(), author = b.getOrDefault("author", "").trim(), subject = b.getOrDefault("subject", "").trim();
            int year = Integer.parseInt(b.getOrDefault("year", "0")), copies = Integer.parseInt(b.getOrDefault("copies", "-1"));
            String lang = b.getOrDefault("language", "English").trim();
            if (title.isBlank() || author.isBlank() || subject.isBlank() || year < 0 || copies < 0) {
                return null;

            }
            return new Book(id, title, author, subject, lang, year, copies);
        } catch (Exception e) {
            return null;
        }
    }

    static Map<String, Object> bookView(Book b) {
        return item("id", b.id(), "title", b.title(), "author", b.author(), "subject", b.subject(), "language", b.language(), "year", b.year(), "copies", b.copies());
    }

    static List<Loan> loans() throws IOException {
        List<Loan> out = new ArrayList<>();
        for (String[] r : readRows(LOANS, 8)) {
            out.add(new Loan(r[0], r[1], r[2], r[3], r[4], r[5], Integer.parseInt(r[6]), Boolean.parseBoolean(r[7])));

        }
        return out;
    }

    static void saveLoans(List<Loan> ls) throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (Loan l : ls) {
            rows.add(new String[]{l.id(), l.email(), l.bookId(), l.issued(), l.due(), l.status(), String.valueOf(l.fine()), String.valueOf(l.charged())});

        }
        saveRows(LOANS, rows);
    }

    static void saveBooks(List<Book> bs) throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (Book b : bs) {
            rows.add(new String[]{b.id(), b.title(), b.author(), b.subject(), b.language(), String.valueOf(b.year()), String.valueOf(b.copies())});

        }
        saveRows(BOOKS, rows);
    }

    static String table(String table) {
        if (!Set.of(BOOKS, USERS, REQUESTS, LOANS, COMPLAINTS).contains(table)) {
            throw new IllegalArgumentException("Unknown database table: " + table);
        }
        return table;
    }

    static String columns(String table) {
        return switch (table(table)) {
            case "books" ->
                "id,title,author,subject,language,year,copies";
            case "users" ->
                "email,name,role,password_hash,approved";
            case "requests" ->
                "email,name,password_hash,created_at";
            case "loans" ->
                "id,email,book_id,issued,due,status,fine,charged";
            case "complaints" ->
                "id,name,email,message,date,status";
            default ->
                throw new IllegalArgumentException();
        };
    }

    static void append(String table, String[] row) throws IOException {
        String tbl = table(table), cols = columns(tbl);
        String marks = String.join(",", Collections.nCopies(row.length, "?"));
        try (Connection c = db(); PreparedStatement s = c.prepareStatement("INSERT INTO " + tbl + " (" + cols + ") VALUES (" + marks + ")")) {
            bind(s, row);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Database insert failed for " + tbl, e);
        }
    }

    static void saveRows(String table, List<String[]> rows) throws IOException {
        String tbl = table(table), cols = columns(tbl);
        String[] names = cols.split(",");
        String marks = String.join(",", Collections.nCopies(names.length, "?"));
        String updates = Arrays.stream(names).skip(1).map(n -> n + "=VALUES(" + n + ")").collect(java.util.stream.Collectors.joining(","));
        try (Connection c = db()) {
            c.setAutoCommit(false);
            try {
                if (!rows.isEmpty())try (PreparedStatement s = c.prepareStatement("INSERT INTO " + tbl + " (" + cols + ") VALUES (" + marks + ") ON DUPLICATE KEY UPDATE " + updates)) {
                    for (String[] r : rows) {
                        bind(s, r);
                        s.addBatch();
                    }
                    s.executeBatch();
                }
                if (rows.isEmpty()) {
                    try (Statement d = c.createStatement()) {
                        d.executeUpdate("DELETE FROM " + tbl);
                    }
                } else {
                    String keyMarks = String.join(",", Collections.nCopies(rows.size(), "?"));
                    try (PreparedStatement d = c.prepareStatement("DELETE FROM " + tbl + " WHERE " + names[0] + " NOT IN (" + keyMarks + ")")) {
                        for (int i = 0; i < rows.size(); i++) {
                            d.setString(i + 1, rows.get(i)[0]);

                        }
                        d.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException("Database update failed for " + tbl, e);
        }
    }

    static void bind(PreparedStatement s, String[] row) throws SQLException {
        for (int i = 0; i < row.length; i++) {
            String value = row[i];
            if (row.length == 7 && (i == 5 || i == 6)) {
                s.setInt(i + 1, Integer.parseInt(value));
            } else if (row.length == 5 && i == 4) {
                s.setInt(i + 1, value.equals("1") || Boolean.parseBoolean(value) ? 1 : 0);
            } else if (row.length == 8 && i == 6) {
                s.setInt(i + 1, Integer.parseInt(value));
            } else if (row.length == 8 && i == 7) {
                s.setInt(i + 1, value.equals("1") || Boolean.parseBoolean(value) ? 1 : 0);
            } else {
                s.setString(i + 1, value);

            }
        }
    }

    static void saveRawRows(String table, List<String[]> rows) throws IOException {
        saveRows(table, rows);
    }

    static List<String[]> readRows(String table, int n) throws IOException {
        List<String[]> out = new ArrayList<>();
        String tbl = table(table);
        try (Connection c = db(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT " + columns(tbl) + " FROM " + tbl)) {
            while (r.next()) {
                String[] row = new String[n];
                for (int i = 0; i < n; i++) {
                    row[i] = r.getString(i + 1);

                }
                if (n == 5) {
                    row[4] = Boolean.toString("1".equals(row[4]) || "true".equalsIgnoreCase(row[4]));

                }
                if (n == 8) {
                    row[7] = Boolean.toString("1".equals(row[7]) || "true".equalsIgnoreCase(row[7]));

                }
                out.add(row);
            }
        } catch (SQLException e) {
            throw new IOException("Database query failed for " + tbl, e);
        }
        return out;
    }

    static String[] decRow(String line) {
        String[] parts = line.split("\\t", -1), r = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            r[i] = new String(Base64.getUrlDecoder().decode(parts[i]), StandardCharsets.UTF_8);

        }
        return r;
    }

    static String hash(String p) throws Exception {
        byte[] b = MessageDigest.getInstance("SHA-256").digest((SALT + p).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(b);
    }

    static Map<String, String> body(HttpExchange x) throws IOException {
        String raw = new String(x.getRequestBody().readNBytes(100_000), StandardCharsets.UTF_8);
        Map<String, String> m = new HashMap<>();
        Matcher q = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|(-?\\d+(?:\\.\\d+)?)|(true|false|null))").matcher(raw);
        while (q.find()) {
            String value = q.group(2) != null ? unescape(q.group(2)) : q.group(3) != null ? q.group(3) : q.group(4);
            m.put(q.group(1), value);

        }
        return m;
    }

    static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\");
    }

    static String cookie(HttpExchange x) {
        String c = x.getRequestHeaders().getFirst("Cookie");
        if (c == null) {
            return null;

        }
        for (String a : c.split(";")) {
            String[] kv = a.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("lms_session")) {
                return kv[1];

            }
        }
        return null;
    }

    static String query(HttpExchange x, String key) {
        String q = x.getRequestURI().getRawQuery();
        if (q == null) {
            return "";

        }
        for (String a : q.split("&")) {
            String[] kv = a.split("=", 2);
            if (kv.length == 2 && URLDecoder.decode(kv[0], StandardCharsets.UTF_8).equals(key)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);

            }
        }
        return "";
    }

    static void headers(HttpExchange x) {
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    }

    static void localCors(HttpExchange x) {
        String origin = x.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            return;

        }
        try {
            URI u = URI.create(origin);
            String host = u.getHost();
            if ("http".equalsIgnoreCase(u.getScheme()) && host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1"))) {
                x.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
                x.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
                x.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                x.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                x.getResponseHeaders().set("Vary", "Origin");
            }
        } catch (Exception ignored) {
        }
    }

    static void send(HttpExchange x, int status, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(status, b.length);
        try (OutputStream o = x.getResponseBody()) {
            o.write(b);
        }
    }

    static void staticFile(HttpExchange x) throws IOException {
        if (!x.getRequestMethod().equals("GET") && !x.getRequestMethod().equals("HEAD")) {
            send(x, 405, "Method not allowed");
            return;
        }
        String raw = x.getRequestURI().getPath();
        if (raw.equals("/")) {
            raw = "/index.html";

        }
        String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        Path f = ROOT.resolve(decoded.substring(1)).normalize();
        if (!f.startsWith(ROOT) || f.startsWith(ROOT.resolve("server")) || !Files.isRegularFile(f)) {
            send(x, 404, "Not found");
            return;
        }
        String n = f.getFileName().toString(), t = n.endsWith(".html") ? "text/html; charset=utf-8" : n.endsWith(".css") ? "text/css; charset=utf-8" : n.endsWith(".js") ? "application/javascript; charset=utf-8" : n.endsWith(".png") ? "image/png" : "application/octet-stream";
        x.getResponseHeaders().set("Content-Type", t);
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        byte[] b = Files.readAllBytes(f);
        x.sendResponseHeaders(200, x.getRequestMethod().equals("HEAD") ? -1 : b.length);
        if (!x.getRequestMethod().equals("HEAD"))try (OutputStream o = x.getResponseBody()) {
            o.write(b);
        }
    }

    static Map<String, Object> item(Object... a) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < a.length; i += 2) {
            m.put(String.valueOf(a[i]), a[i + 1]);

        }
        return m;
    }

    static String json(Object o) {
        if (o == null) {
            return "null";

        }
        if (o instanceof String s) {
            return "\"" + esc(s) + "\"";

        }
        if (o instanceof Number || o instanceof Boolean) {
            return o.toString();

        }
        if (o instanceof Map<?, ?> m) {
            StringJoiner j = new StringJoiner(",", "{", "}");
            m.forEach((k, v) -> j.add(json(String.valueOf(k)) + ":" + json(v)));
            return j.toString();
        }
        if (o instanceof Iterable<?> i) {
            StringJoiner j = new StringJoiner(",", "[", "]");
            for (Object v : i) {
                j.add(json(v));

            }
            return j.toString();
        }
        return json(o.toString());
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
