from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import mm
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, Flowable, KeepTogether
from reportlab.pdfbase.pdfmetrics import stringWidth
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'output' / 'pdf' / 'library-management-system-class-diagram-and-program-description.pdf'
OUT.parent.mkdir(parents=True, exist_ok=True)

NAVY = colors.HexColor('#102B52')
BLUE = colors.HexColor('#2869CF')
PALE = colors.HexColor('#EFF5FC')
PALE2 = colors.HexColor('#F7F9FC')
INK = colors.HexColor('#20344F')
MUTED = colors.HexColor('#5E6F86')
TEAL = colors.HexColor('#138A83')
GOLD = colors.HexColor('#F2B84B')
BORDER = colors.HexColor('#D8E2EF')
GREEN = colors.HexColor('#19744E')
RED = colors.HexColor('#A33C38')
PAGE_W, PAGE_H = A4

styles = getSampleStyleSheet()
styles.add(ParagraphStyle(name='CoverEyebrow', fontName='Helvetica-Bold', fontSize=9, leading=13, textColor=colors.white, spaceAfter=8, tracking=1.2))
styles.add(ParagraphStyle(name='CoverTitle', fontName='Helvetica-Bold', fontSize=28, leading=33, textColor=colors.white, spaceAfter=10))
styles.add(ParagraphStyle(name='CoverSub', fontName='Helvetica', fontSize=12, leading=18, textColor=colors.HexColor('#DCE9F8')))
styles.add(ParagraphStyle(name='H1x', fontName='Helvetica-Bold', fontSize=20, leading=25, textColor=NAVY, spaceAfter=8))
styles.add(ParagraphStyle(name='H2x', fontName='Helvetica-Bold', fontSize=12, leading=16, textColor=NAVY, spaceBefore=8, spaceAfter=5))
styles.add(ParagraphStyle(name='Bodyx', fontName='Helvetica', fontSize=9, leading=13, textColor=INK, spaceAfter=6))
styles.add(ParagraphStyle(name='Smallx', fontName='Helvetica', fontSize=7.8, leading=10.5, textColor=INK))
styles.add(ParagraphStyle(name='SmallMuted', fontName='Helvetica', fontSize=7.8, leading=10.5, textColor=MUTED))
styles.add(ParagraphStyle(name='TableHeadx', fontName='Helvetica-Bold', fontSize=8, leading=10, textColor=colors.white))
styles.add(ParagraphStyle(name='TableBoldx', fontName='Helvetica-Bold', fontSize=8, leading=10.5, textColor=NAVY))
styles.add(ParagraphStyle(name='CardTitle', fontName='Helvetica-Bold', fontSize=10, leading=13, textColor=NAVY, spaceAfter=4))
styles.add(ParagraphStyle(name='CardBody', fontName='Helvetica', fontSize=8.2, leading=11, textColor=INK))
styles.add(ParagraphStyle(name='CenterNote', fontName='Helvetica', fontSize=8, leading=11, textColor=MUTED, alignment=TA_CENTER))

P = lambda text, style='Bodyx': Paragraph(text, styles[style])

def cell(text, style='Smallx'):
    return Paragraph(text, styles[style])

def make_table(data, widths, header=True, font_size=8, padd=6):
    t = Table(data, colWidths=widths, repeatRows=1 if header else 0, hAlign='LEFT')
    rules = [
        ('VALIGN', (0,0), (-1,-1), 'TOP'),
        ('LEFTPADDING', (0,0), (-1,-1), padd), ('RIGHTPADDING', (0,0), (-1,-1), padd),
        ('TOPPADDING', (0,0), (-1,-1), padd), ('BOTTOMPADDING', (0,0), (-1,-1), padd),
        ('GRID', (0,0), (-1,-1), 0.45, BORDER),
        ('BACKGROUND', (0,0), (-1,0), NAVY if header else PALE2),
        ('ROWBACKGROUNDS', (0,1 if header else 0), (-1,-1), [colors.white, PALE2]),
    ]
    t.setStyle(TableStyle(rules))
    return t

class ClassDiagram(Flowable):
    def __init__(self, width=511, height=335):
        super().__init__()
        self.width, self.height = width, height
    def box(self, c, x, y, w, h, title, lines, fill, stroke, title_color=NAVY, small=7.3):
        c.setFillColor(fill); c.setStrokeColor(stroke); c.setLineWidth(1)
        c.roundRect(x, y, w, h, 7, stroke=1, fill=1)
        c.setFillColor(title_color); c.setFont('Helvetica-Bold', 9)
        c.drawString(x+8, y+h-15, title)
        c.setStrokeColor(stroke); c.setLineWidth(.5); c.line(x+7, y+h-21, x+w-7, y+h-21)
        c.setFillColor(INK); c.setFont('Helvetica', small)
        yy = y+h-33
        for line in lines:
            c.drawString(x+8, yy, line)
            yy -= 11
    def arrow(self, c, x1, y1, x2, y2, color=BLUE, dashed=False):
        c.saveState(); c.setStrokeColor(color); c.setFillColor(color); c.setLineWidth(1.2)
        if dashed: c.setDash(3,2)
        c.line(x1,y1,x2,y2)
        import math
        angle = math.atan2(y2-y1, x2-x1)
        length=6
        p1=(x2-length*math.cos(angle-0.45), y2-length*math.sin(angle-0.45))
        p2=(x2-length*math.cos(angle+0.45), y2-length*math.sin(angle+0.45))
        path=c.beginPath(); path.moveTo(x2,y2); path.lineTo(*p1); path.lineTo(*p2); path.close()
        c.drawPath(path,stroke=0,fill=1); c.restoreState()
    def draw(self):
        c=self.canv
        self.box(c, 4, 202, 132, 105, 'Browser client', ['HTML pages', 'CSS presentation', 'app.js: forms and UI', 'Session cookie included'], PALE, BLUE)
        self.box(c, 171, 202, 176, 105, 'LibraryServer', ['Java JDK HttpServer', 'Routes and role checks', 'Business rules and validation', 'JDBC persistence and static files'], colors.HexColor('#E9F0FB'), BLUE)
        self.box(c, 385, 202, 122, 105, 'MySQL', ['library_management', 'Accounts and resources', 'Loans, returns, fines', 'Complaints and content'], colors.HexColor('#E8F6F2'), TEAL)
        self.arrow(c, 136, 252, 171, 252, BLUE)
        c.setFont('Helvetica', 6.7); c.setFillColor(MUTED); c.drawCentredString(153, 263, 'HTTP / JSON')
        self.arrow(c, 347, 252, 385, 252, TEAL)
        c.setFillColor(MUTED); c.drawCentredString(366, 263, 'JDBC')
        # Model classes
        self.box(c, 5, 46, 152, 102, '<<record>> User', ['email, name, role', 'password hash, approved', 'Represents an authenticated', 'or pending member account'], colors.white, BORDER, small=7.2)
        self.box(c, 179, 46, 152, 102, '<<record>> Book', ['id, title, author, subject', 'language, year, copies', 'available copies are derived', 'from active loans'], colors.white, BORDER, small=7.2)
        self.box(c, 353, 46, 152, 102, '<<record>> Loan', ['id, email, bookId', 'issued, due, status', 'fine, charged', 'Fine payment/cancellation totals', 'are stored in MySQL'], colors.white, BORDER, small=7.2)
        # LibraryServer dependency bus to record models
        c.setStrokeColor(MUTED); c.setLineWidth(.8); c.setDash(3,2)
        c.line(259,202,259,174); c.line(81,174,429,174)
        c.line(81,174,81,148); c.line(255,174,255,148); c.line(429,174,429,148)
        c.setDash()
        self.arrow(c,81,158,81,148,MUTED,True); self.arrow(c,255,158,255,148,MUTED,True); self.arrow(c,429,158,429,148,MUTED,True)
        c.setFillColor(MUTED); c.setFont('Helvetica-Oblique',7)
        c.drawCentredString(255, 160, 'LibraryServer creates and processes these records')
        c.setFillColor(MUTED); c.setFont('Helvetica',7)
        c.drawString(5, 21, 'External libraries: JDK HTTP server, JDBC API, and MySQL Connector/J.')

class Card(Flowable):
    def __init__(self, title, body, width=150, height=84, color=BLUE):
        super().__init__(); self.width=width; self.height=height; self.title=title; self.body=body; self.color=color
    def draw(self):
        c=self.canv; c.setFillColor(PALE2); c.setStrokeColor(BORDER); c.roundRect(0,0,self.width,self.height,7,stroke=1,fill=1)
        c.setFillColor(self.color); c.roundRect(0,self.height-5,self.width,5,2,stroke=0,fill=1)
        c.setFillColor(NAVY); c.setFont('Helvetica-Bold',10); c.drawString(10,self.height-22,self.title)
        style=ParagraphStyle('card-temp',fontName='Helvetica',fontSize=8,leading=11,textColor=INK)
        p=Paragraph(self.body,style); p.wrapOn(c,self.width-20,self.height-34); p.drawOn(c,10,10)


def page_chrome(canvas, doc):
    canvas.saveState()
    canvas.setFillColor(NAVY); canvas.rect(0, PAGE_H-7, PAGE_W, 7, stroke=0, fill=1)
    canvas.setFont('Helvetica-Bold',7.5); canvas.setFillColor(NAVY)
    canvas.drawString(doc.leftMargin, PAGE_H-25, 'BBAU CENTRAL LIBRARY | SYSTEM DESIGN')
    canvas.setStrokeColor(BORDER); canvas.line(doc.leftMargin, 29, PAGE_W-doc.rightMargin, 29)
    canvas.setFont('Helvetica',7); canvas.setFillColor(MUTED)
    canvas.drawString(doc.leftMargin, 17, 'Prepared from the project source and MySQL schema')
    canvas.drawRightString(PAGE_W-doc.rightMargin, 17, f'Page {doc.page}')
    canvas.restoreState()


doc=SimpleDocTemplate(str(OUT), pagesize=A4, rightMargin=42, leftMargin=42, topMargin=43, bottomMargin=40,
                      title='Library Management System - Class Diagram and Program Description',
                      author='BBAU Central Library project documentation', subject='Java and MySQL library management system')
W=PAGE_W-doc.leftMargin-doc.rightMargin
story=[]

# Page 1 - overview
hero=Table([[[
    P('PROJECT DOCUMENTATION', 'CoverEyebrow'),
    P('Library Management<br/>System', 'CoverTitle'),
    P('Class diagram and program description', 'CoverSub'),
    Spacer(1,12),
    P('A guide to the Java backend, browser frontend, MySQL data model, roles, and core workflows.', 'CoverSub')
]]], colWidths=[W])
hero.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,-1),NAVY),('BOX',(0,0),(-1,-1),0,NAVY),('LEFTPADDING',(0,0),(-1,-1),22),('RIGHTPADDING',(0,0),(-1,-1),22),('TOPPADDING',(0,0),(-1,-1),24),('BOTTOMPADDING',(0,0),(-1,-1),24)]))
story += [Spacer(1,17), hero, Spacer(1,19), P('What the program does','H2x'), P('The Library Management System is a local web application for managing a library catalogue, member access, borrowing, return approvals, fines, complaints, and homepage notices. Admins, librarians, and members use separate workspaces with permissions enforced by the Java API.','Bodyx')]
card_data=[[Card('Catalogue','Search resources and check availability. Members can issue available items online.',W/3-8,87,BLUE), Card('Role-based workspaces','Admins manage members and site content. Librarians manage resources and returns. Members manage their loans.',W/3-8,87,TEAL), Card('Borrowing and fines','Loans have due dates, overdue fines, paid/canceled amounts, and staff-approved returns.',W/3-8,87,GOLD)]]
ct=Table(card_data,colWidths=[W/3]*3)
ct.setStyle(TableStyle([('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),0),('RIGHTPADDING',(0,0),(-1,-1),5),('TOPPADDING',(0,0),(-1,-1),4),('BOTTOMPADDING',(0,0),(-1,-1),4)]))
story += [ct, Spacer(1,13), P('Technology at a glance','H2x')]
stack=[
 [cell('Layer','TableHeadx'),cell('Implementation','TableHeadx'),cell('Responsibility','TableHeadx')],
 [cell('Frontend','TableBoldx'),cell('HTML, CSS, JavaScript'),cell('Pages, forms, catalogue interactions, and role-specific dashboards.')],
 [cell('Backend','TableBoldx'),cell('Java 17+ and JDK HTTP server'),cell('JSON API, session handling, role checks, validation, and business rules.')],
 [cell('Database','TableBoldx'),cell('MySQL via JDBC and Connector/J'),cell('Persistent accounts, resources, loans, return requests, fines, complaints, and content.')],
]
story += [make_table(stack,[72,150,W-222],padd=6),Spacer(1,10),P('<b>Implementation note:</b> The backend is centered in one Java class, <i>LibraryServer</i>. The data records <i>User</i>, <i>Book</i>, and <i>Loan</i> are nested Java records. The browser files are served by the same local Java process.','SmallMuted'),PageBreak()]

# Page 2 - diagram
story += [P('Class diagram and application architecture','H1x'),P('The diagram separates the browser client, the Java service, the MySQL database, and the Java record types. The project is intentionally compact: request routing, service logic, and database access are implemented together in LibraryServer.','Bodyx'),Spacer(1,5),ClassDiagram(W,335),Spacer(1,4)]
legend=Table([[cell('<b>Solid arrows</b> show request or persistence flow. <b>Dashed connectors</b> show Java record dependencies. Browser pages are components, not Java classes.','Smallx')]],colWidths=[W])
legend.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,-1),PALE),('BOX',(0,0),(-1,-1),.6,BORDER),('LEFTPADDING',(0,0),(-1,-1),10),('RIGHTPADDING',(0,0),(-1,-1),10),('TOPPADDING',(0,0),(-1,-1),8),('BOTTOMPADDING',(0,0),(-1,-1),8)]))
story += [legend,Spacer(1,13),P('How a request moves through the system','H2x'),P('A user opens a page such as the catalogue or account workspace. JavaScript sends an HTTP request to a route under /api and includes the session cookie. LibraryServer authenticates the user, checks role access where needed, applies the operation, reads or writes MySQL using JDBC, and returns JSON for the page to display.','Bodyx'),P('The Java records are lightweight value objects. MySQL remains the persistent source of truth for application records.','SmallMuted'),PageBreak()]

# Page 3 - classes and database
story += [P('Java classes and data model','H1x'),P('The Java backend has one application class and three nested records. This reflects the current source layout; database tables are not separate Java entity classes.','Bodyx')]
classes=[
 [cell('Type','TableHeadx'),cell('Fields / responsibilities','TableHeadx'),cell('Role in the program','TableHeadx')],
 [cell('LibraryServer','TableBoldx'),cell('Configuration, main(), HTTP routes, auth, validation, fine calculations, persistence helpers, static-file serving.'),cell('Starts the service, exposes JSON endpoints, enforces roles, and coordinates the browser, business rules, and database.')],
 [cell('User record','TableBoldx'),cell('email, name, role, hash, approved'),cell('Represents an account used for authentication and permission checks.')],
 [cell('Book record','TableBoldx'),cell('id, title, author, subject, language, year, copies'),cell('Represents a catalogue resource and its total copy count. Availability is derived from active loans.')],
 [cell('Loan record','TableBoldx'),cell('id, email, bookId, issued, due, status, fine, charged'),cell('Represents a borrowing transaction. Additional paid, charged-total, and canceled-total values are stored in MySQL.')],
]
story += [make_table(classes,[94,206,W-300],padd=6),Spacer(1,11),P('MySQL tables','H2x'),P('The schema is defined in server/mysql-schema.sql. Startup creates missing tables and applies compatibility columns for existing databases. The first startup also imports the one-time MySQL snapshot if its migration has not yet been recorded.','Bodyx')]
dbrows=[
 [cell('Table','TableHeadx'),cell('Stored information','TableHeadx'),cell('Primary use','TableHeadx')],
 [cell('users','TableBoldx'),cell('Email, name, role, password hash, approval flag.'),cell('Sign-in and role permissions.')],
 [cell('requests','TableBoldx'),cell('Pending member registration details and password hash.'),cell('Admin approval queue.')],
 [cell('books','TableBoldx'),cell('Resource metadata and number of copies.'),cell('Catalogue and availability.')],
 [cell('loans','TableBoldx'),cell('Borrower/resource IDs, dates, status, fine, charged flag, paid/charged/canceled totals.'),cell('Borrowing history, balances, and fine state.')],
 [cell('return_requests','TableBoldx'),cell('Loan ID, member, request time, status, reviewer, review time.'),cell('Staff-controlled return approval.')],
 [cell('complaints','TableBoldx'),cell('Name, email, message, date, status.'),cell('Admin complaint handling.')],
 [cell('announcements / events','TableBoldx'),cell('Homepage and event content.'),cell('Admin-managed public information.')],
 [cell('app_migrations','TableBoldx'),cell('Applied migration names and timestamps.'),cell('Prevents duplicate one-time import.')],
]
story += [make_table(dbrows,[113,221,W-334],padd=5),Spacer(1,7),P('Foreign references in the current schema are stored as email and resource IDs in loan rows. Availability is calculated by comparing book copies with the count of active loans.','SmallMuted'),PageBreak()]

# Page 4 - roles
story += [P('Roles and access','H1x'),P('The frontend provides separate role workspaces. The backend checks authorization on each protected route; hiding a button in the browser is not the only access control.','Bodyx')]
roles=[
 [cell('Admin','TableHeadx'),cell('Librarian','TableHeadx'),cell('Member','TableHeadx')],
 [cell('<b>Membership</b><br/>Review registrations; add and remove members.','Smallx'),cell('<b>Resources</b><br/>Add, edit, remove resources, and change copy counts.','Smallx'),cell('<b>Catalogue</b><br/>Search resources and see availability.','Smallx')],
 [cell('<b>Complaints</b><br/>Read and resolve contact messages.','Smallx'),cell('<b>Borrower ledger</b><br/>View loan history and fine state.','Smallx'),cell('<b>Borrowing</b><br/>Issue eligible resources and renew eligible loans.','Smallx')],
 [cell('<b>Loans and returns</b><br/>Review loans, fine charges, payments, cancellations, and return approvals.','Smallx'),cell('<b>Returns</b><br/>Approve or reject pending return requests.','Smallx'),cell('<b>Returns</b><br/>Request a return; the item remains issued until staff approval.','Smallx')],
 [cell('<b>Homepage</b><br/>Manage announcements and events.','Smallx'),cell('<b>Fine actions</b><br/>Charge or cancel unpaid fines and mark them paid.','Smallx'),cell('<b>Account</b><br/>View due dates, return status, and outstanding fines.','Smallx')],
]
story += [make_table(roles,[W/3]*3,padd=8),Spacer(1,14),P('Core workflows','H2x')]
flow=[
 [cell('Workflow','TableHeadx'),cell('Sequence','TableHeadx')],
 [cell('Registration','TableBoldx'),cell('Member submits a registration request. The account stays pending until an admin approves it. Once approved, the account can sign in with the member role.')],
 [cell('Issue a resource','TableBoldx'),cell('Member searches the catalogue. The server calculates available copies from total copies minus active loans, then creates a 14-day loan when a copy is available.')],
 [cell('Return a resource','TableBoldx'),cell('Member submits a return request. A librarian or admin reviews it. The resource is checked in only after approval; rejection leaves the loan active.')],
 [cell('Fine lifecycle','TableBoldx'),cell('Overdue fines accrue at INR 10 per day. Staff can add a manual fine, record an overdue charge, mark an outstanding fine as paid, or cancel its unpaid portion. A return cannot be approved while a fine remains unpaid.')],
]
story += [make_table(flow,[105,W-105],padd=7),Spacer(1,9),P('Fine accounting separates charges, payments, and cancellations. Only amounts marked paid contribute to the collected total. Canceled amounts reduce the member balance but are not counted as collected.','SmallMuted'),PageBreak()]

# Page 5 - runtime and endpoints
story += [P('Runtime, API, and operating notes','H1x'),P('The site runs locally as a Java process that serves the HTML/CSS/JavaScript files and the JSON API. MySQL is required for persistent data. The browser uses the printed localhost URL while the server console remains open.','Bodyx')]
startrows=[
 [cell('Startup step','TableHeadx'),cell('What happens','TableHeadx')],
 [cell('1. Start','TableBoldx'),cell('Run start.bat. It checks MySQL readiness and prompts for the database password if needed.')],
 [cell('2. Prepare','TableBoldx'),cell('The script compiles LibraryServer.java. The server creates/selects the configured MySQL database, applies schema changes, imports the one-time snapshot when needed, and seeds missing demo rows.')],
 [cell('3. Serve','TableBoldx'),cell('The Java HTTP server starts on port 8081 or the next free port up to 8180, then serves the homepage and API.')],
]
story += [make_table(startrows,[105,W-105],padd=7),Spacer(1,10),P('Representative API routes','H2x')]
apirows=[
 [cell('Route','TableHeadx'),cell('Purpose','TableHeadx')],
 [cell('POST /api/login','TableBoldx'),cell('Authenticate credentials and requested role; set an HttpOnly session cookie.')],
 [cell('GET /api/account','TableBoldx'),cell('Return current account details, loans, fine amounts, paid/canceled state, and balance.')],
 [cell('GET /api/books','TableBoldx'),cell('Search resources and calculate available copies.')],
 [cell('POST /api/loans','TableBoldx'),cell('Issue an available catalogue item to an authenticated member.')],
 [cell('GET /api/staff/loans','TableBoldx'),cell('Return the borrower and fine ledger to admin/librarian accounts.')],
 [cell('POST /api/staff/returns/{id}','TableBoldx'),cell('Approve or reject a pending return. Approval is blocked if any balance remains unpaid.')],
 [cell('POST /api/staff/fines/...','TableBoldx'),cell('Staff-only actions to charge, pay, or cancel a fine.')],
]
story += [make_table(apirows,[172,W-172],padd=5),Spacer(1,10),P('Configuration and design boundaries','H2x'),P('Database connection values can be set with LMS_DB_HOST, LMS_DB_PORT, LMS_DB_NAME, LMS_DB_USER, and LMS_DB_PASSWORD. The default database name is library_management. The server keeps login sessions in memory, so restarting the process signs users out. This is a local demonstration system; it is not connected to the university identity provider or a live OPAC. Replace demo accounts and review authentication, transport security, backups, and deployment controls before exposing it publicly.','Bodyx'),Spacer(1,5),P('Source of truth for this guide: server/LibraryServer.java, server/mysql-schema.sql, README.md, and the HTML/JavaScript role pages.','SmallMuted')]

doc.build(story, onFirstPage=page_chrome, onLaterPages=page_chrome)
print(OUT)
