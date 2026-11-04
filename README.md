# BBAU Central Library

## Start the site

1. Install JDK 17 or newer and MySQL Server.
2. Run `start.bat`. It checks the local MySQL port and starts the installed MySQL Windows service if needed. Windows may show a User Account Control prompt to approve starting the service. The default connection is `127.0.0.1:3306`, database `library_management`, user `root`.
3. If your MySQL uses a different host, port, database, username, or Windows service name, set those values in the VS Code PowerShell terminal before starting:

   ```powershell
   $env:LMS_DB_HOST = "127.0.0.1"
   $env:LMS_DB_PORT = "3306"
   $env:LMS_DB_NAME = "library_management"
   $env:LMS_DB_USER = "root"
   $env:LMS_DB_SERVICE_NAME = "MySQL2610"
   .\start.bat
   ```

   `start.bat` securely prompts for the MySQL password; press Enter if the account has no password. You can also set `LMS_DB_PASSWORD` in the terminal before running it.
4. Keep the server console open. It compiles the Java code, creates the `library_management` database when needed (the MySQL user must have permission), applies `server/mysql-schema.sql`, and opens the homepage. If a web port is occupied, it tries the next port through 8180.

The app uses MySQL through the bundled MySQL Connector/J driver. `server/mysql-schema.sql` defines the LMS tables and indexes for accounts/roles, pending membership requests, resources, loans and loan fines, return approvals, complaints, announcements, and events. Java applies this schema at startup to the database selected by `LMS_DB_NAME`. On the first successful startup, the app imports the one-time data snapshot in `server/mysql-import` into MySQL, preserving existing MySQL records and adding missing records. It records the completed import in `app_migrations`; later starts read and write MySQL only. Original files are retained in `server/legacy-backup` as a backup and are not used by the app.

## Demo logins

- Admin: `admin@bbau.ac.in` / `Admin123!`
- Librarian: `librarian@bbau.ac.in` / `Librarian123!`
- Member: `student@bbau.ac.in` / `Library123!`

Open `account.html`, choose the role, and sign in. Successful logins open the dedicated `admin.html`, `librarian.html`, or `member.html` workspace. New members can register from the account page; registrations stay pending until an admin approves them.

## Role features

- **Admin:** approve or reject registrations; add/remove members; review and resolve contact complaints; review loans and charge overdue fines; manage resources; add/remove homepage announcements and events from separate Admin navigation pages.
- **Librarian:** add, edit and remove resources; change copy counts; view the full borrower and fine ledger; charge overdue fines; approve or reject return requests. The backend protects copies that are currently issued.
- **Member:** search resources and availability; issue available items online; see due dates and fines; renew eligible loans and request resource returns. Return requests require Admin or Librarian approval.

Overdue fines accrue at ₹10 per day. The site is a local demo, not connected to the university identity provider or live OPAC. Replace the demo accounts and connect approved identity and security services before public deployment.
