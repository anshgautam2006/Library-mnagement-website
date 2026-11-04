(() => {
  const q = (selector, root = document) => root.querySelector(selector);
  const el = (tag, cls = '', text = '') => {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== undefined) node.textContent = text;
    return node;
  };
  const note = (node, message, success = false) => {
    if (!node) return;
    node.textContent = message;
    node.className = `form-message${success ? ' success' : ''}`;
  };
  let apiOriginPromise;
  const findApiOrigin = async () => {
    const local = ['localhost', '127.0.0.1'].includes(location.hostname);
    const probe = async origin => {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 700);
      try {
        const response = await fetch(`${origin}/api/health`, {
          credentials: 'include', cache: 'no-store', headers: { Accept: 'application/json' }, signal: controller.signal,
        });
        if (!response.ok || !response.headers.get('content-type')?.includes('application/json')) return false;
        const data = await response.json();
        return data.service === 'bbaulms-library' && data.apiVersion === 7;
      } catch (_) { return false; }
      finally { clearTimeout(timeout); }
    };
    if (await probe(location.origin)) return location.origin;
    if (local) {
      const hosts = [location.hostname, location.hostname === 'localhost' ? '127.0.0.1' : 'localhost'];
      for (const host of hosts) {
        const candidates = Array.from({ length: 100 }, (_, i) => `http://${host}:${8081 + i}`);
        const healthy = await Promise.all(candidates.map(async origin => ({ origin, ok: await probe(origin) })));
        const match = healthy.find(item => item.ok);
        if (match) return match.origin;
      }
    }
    throw new Error('No current Library Java server found. Close older server windows, run start.bat again, and use the localhost address it prints.');
  };
  const api = async (path, options = {}) => {
    let response;
    try {
      apiOriginPromise ||= findApiOrigin().catch(error => { apiOriginPromise = null; throw error; });
      const origin = await apiOriginPromise;
      response = await fetch(`${origin}${path}`, {
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        ...options,
      });
    } catch (_) {
      throw new Error('Cannot reach the Library Java server. Run start.bat and use the localhost address it prints.');
    }
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || `Request failed (${response.status}).`);
    return data;
  };
  const section = title => {
    const box = el('section', 'role-section');
    box.append(el('h3', '', title));
    return box;
  };

  // Search and issue available resources.
  const catalogSearch = q('#catalog-search');
  if (catalogSearch) {
    const input = q('[name="q"]', catalogSearch);
    const type = q('[name="type"]', catalogSearch);
    const result = q('#catalog-results');
    const notice = q('#catalog-message');
    const params = new URLSearchParams(location.search);
    input.value = params.get('q') || '';
    if (params.get('type')) type.value = params.get('type');
    const load = async () => {
      result.replaceChildren(el('p', 'muted', 'Loading catalogue…'));
      try {
        const data = await api(`/api/books?q=${encodeURIComponent(input.value)}&type=${encodeURIComponent(type.value)}`);
        result.replaceChildren(el('p', 'muted', `${data.count} resources found`));
        if (!data.items.length) { result.append(el('p', '', 'No matches. Try another title, author or subject.')); return; }
        const list = el('div', 'book-results');
        for (const book of data.items) {
          const card = el('article', 'book-card');
          const info = el('div');
          info.append(el('h3', '', book.title), el('p', '', `by ${book.author}`), el('small', '', `${book.subject} · ${book.language} · ${book.year} · ${book.availableCopies} of ${book.copies} available`));
          const issue = el('button', 'reserve-button', book.availableCopies ? 'Issue online' : 'Unavailable');
          issue.type = 'button';
          issue.disabled = !book.availableCopies;
          issue.addEventListener('click', async () => {
            try {
              const issued = await api('/api/loans', { method: 'POST', body: JSON.stringify({ bookId: book.id }) });
              note(notice, issued.message, true);
              issue.textContent = 'Issued';
              issue.disabled = true;
            } catch (error) {
              note(notice, error.message);
              if (/sign in/i.test(error.message)) location.href = 'account.html';
            }
          });
          card.append(info, issue);
          list.append(card);
        }
        result.append(list);
      } catch (error) { result.replaceChildren(el('p', 'form-message', error.message)); }
    };
    catalogSearch.addEventListener('submit', event => {
      event.preventDefault();
      const url = new URL(location.href);
      url.searchParams.set('q', input.value);
      url.searchParams.set('type', type.value);
      history.replaceState({}, '', url);
      load();
    });
    load();
  }

  // Login and member registration.
  const login = q('#login-form');
  const signup = q('#signup-form');
  const chooser = q('#login-chooser');
  const panel = q('#account-panel, #role-dashboard');
  const status = q('#login-message, #role-message');
  const requiredRole = document.body.dataset.rolePage || '';
  const adminSection = document.body.dataset.adminSection || 'overview';
  const librarianSection = document.body.dataset.librarianSection || 'overview';
  const registerToggle = q('#register-toggle');
  const chooseLoginRole = role => {
    if (!login || !chooser || !['admin', 'librarian', 'member'].includes(role)) return;
    login.elements.role.value = role;
    q('#login-role-title').textContent = `${role[0].toUpperCase()}${role.slice(1)} sign in`;
    chooser.hidden = true;
    login.hidden = false;
    if (signup) signup.hidden = true;
    note(status, '');
    login.elements.email.focus();
  };
  if (login && chooser) {
    chooser.querySelectorAll('[data-login-role]').forEach(choice => choice.addEventListener('click', () => chooseLoginRole(choice.dataset.loginRole)));
    chooseLoginRole(new URLSearchParams(location.search).get('role'));
    q('#role-back')?.addEventListener('click', () => {
      login.reset();
      login.hidden = true;
      chooser.hidden = false;
      if (signup) signup.hidden = true;
      note(status, '');
    });
  }
  registerToggle?.addEventListener('click', () => {
    signup.hidden = !signup.hidden;
    if (!signup.hidden) signup.scrollIntoView({ behavior: 'smooth', block: 'center' });
  });
  if (signup) signup.addEventListener('submit', async event => {
    event.preventDefault();
    const submit = signup.querySelector('[type="submit"]');
    submit.disabled = true;
    try {
      const data = await api('/api/signup', { method: 'POST', body: JSON.stringify(Object.fromEntries(new FormData(signup))) });
      note(q('#signup-message'), `${data.message} You can sign in after an administrator approves your request.`, true);
      signup.reset();
    } catch (error) { note(q('#signup-message'), error.message); }
    finally { submit.disabled = false; }
  });

  async function loadAccount() {
    try {
      const data = await api('/api/account');
      const role = data.user.role;
      if (!requiredRole && login && role) { location.replace(`${role}.html`); return; }
      if (requiredRole && role !== requiredRole) { location.replace(`${role}.html`); return; }
      if (!panel) return;
      if (login) login.hidden = true;
      if (chooser) chooser.hidden = true;
      if (signup) signup.hidden = true;
      panel.hidden = false;
      panel.replaceChildren();
      const head = el('div', 'account-heading');
      const heading = el('div');
      heading.append(el('h2', '', `${data.user.name} · ${role.toUpperCase()}`), el('p', 'muted', data.user.email));
      const logout = el('button', 'secondary-button', 'Sign out');
      logout.type = 'button';
      logout.addEventListener('click', async () => { await api('/api/logout', { method: 'POST', body: '{}' }); location.href = 'account.html'; });
      head.append(heading, logout);
      panel.append(head);
      if (role === 'member') renderMember(panel, data);
      if (role === 'librarian') await renderLibrarian(panel, librarianSection);
      if (role === 'admin') {
        const adminData = await api('/api/admin/data');
        await renderAdmin(panel, adminData, adminSection);
      }
    } catch (error) {
      if (requiredRole && error.message === 'Please sign in to continue.') { location.replace(`account.html?role=${encodeURIComponent(requiredRole)}`); return; }
      if (requiredRole) note(status, error.message);
      else if (login && error.message.startsWith('Cannot reach')) note(status, error.message);
    }
  }

  function renderMember(root, data) {
    const box = section('My borrowed resources');
    if (!data.loans.length) box.append(el('p', 'muted', 'No current or past loans. Search the catalogue to issue a resource.'));
    for (const loan of data.loans) {
      const row = el('div', 'loan-row');
      const info = el('div');
      const outstanding = Number(loan.fineOutstanding ?? (Number(loan.fine) - Number(loan.finePaid || 0) - Number(loan.fineCancelled || 0))) || 0;
      const cancelled = Number(loan.fineCancelled) || 0;
      const fineText = loan.fine ? ` · fine ₹${loan.fine}${outstanding > 0 ? ` · ₹${outstanding} outstanding` : loan.finePaid > 0 ? ' · paid' : cancelled > 0 ? ' · canceled' : ''}` : '';
      info.append(el('b', '', loan.title), el('small', '', `${loan.status}${loan.returnPending ? ' · return request awaiting staff approval' : ''} · due ${loan.dueDate}${fineText}`));
      row.append(info);
      if (loan.status === 'issued') {
        if (loan.returnPending) row.append(el('span', 'pending-badge', 'Awaiting staff approval'));
        else {
          const renew = el('button', 'secondary-button', 'Renew 14 days');
          renew.addEventListener('click', async () => { try { await api(`/api/loans/${loan.id}/renew`, { method: 'POST', body: '{}' }); await loadAccount(); } catch (error) { note(status, error.message); } });
          const ret = el('button', 'reserve-button', 'Request return');
          ret.addEventListener('click', async () => { ret.disabled = true; try { const result = await api(`/api/loans/${loan.id}/return-request`, { method: 'POST', body: '{}' }); note(status, result.message, true); await loadAccount(); } catch (error) { ret.disabled = false; note(status, error.message); } });
          row.append(renew, ret);
        }
      }
      box.append(row);
    }
    box.append(el('p', 'muted', `Outstanding fines: ₹${data.fineBalance}`));
    const search = el('a', 'back-home', 'Search resources and check availability →');
    search.href = 'catalog.html';
    box.append(search);
    root.append(box);
  }

  function resourceManager(root) {
    const wrap = section('Resource management');
    const form = el('form', 'catalog-form');
    form.innerHTML = '<label>Add or update a book or resource</label><div class="resource-fields"><input name="id" placeholder="ID (optional for new)" aria-label="Resource ID"><input name="title" required placeholder="Title"><input name="author" required placeholder="Author"><input name="subject" required placeholder="Subject"><input name="language" value="English" placeholder="Language"><input name="year" type="number" min="0" required placeholder="Year"><input name="copies" type="number" min="0" required placeholder="Total copies"><button type="submit">Add resource</button></div><p class="form-message" role="status"></p>';
    const list = el('div', 'resource-admin-list');
    wrap.append(form, list);
    root.append(wrap);
    const load = async () => {
      const data = await api('/api/books');
      list.replaceChildren();
      for (const book of data.items) {
        const row = el('div', 'book-card');
        const info = el('div');
        info.append(el('b', '', `${book.id} · ${book.title}`), el('small', '', `${book.author} · ${book.subject} · ${book.availableCopies}/${book.copies} available`));
        const edit = el('button', 'secondary-button', 'Edit');
        edit.addEventListener('click', () => {
          for (const key of ['id', 'title', 'author', 'subject', 'language', 'year', 'copies']) form.elements[key].value = book[key];
          form.elements.id.readOnly = true;
          form.querySelector('[type="submit"]').textContent = 'Save changes';
          form.scrollIntoView({ behavior: 'smooth', block: 'center' });
        });
        const remove = el('button', 'secondary-button', 'Remove');
        remove.addEventListener('click', async () => {
          if (!confirm(`Remove ${book.title}?`)) return;
          try { await api(`/api/books/${encodeURIComponent(book.id)}`, { method: 'DELETE' }); await load(); }
          catch (error) { note(form.querySelector('.form-message'), error.message); }
        });
        row.append(info, edit, remove);
        list.append(row);
      }
    };
    form.addEventListener('submit', async event => {
      event.preventDefault();
      const values = Object.fromEntries(new FormData(form));
      const id = values.id;
      try {
        await api(id ? `/api/books/${encodeURIComponent(id)}` : '/api/books', { method: id ? 'PUT' : 'POST', body: JSON.stringify(values) });
        note(form.querySelector('.form-message'), id ? 'Resource updated.' : 'Resource added.', true);
        form.reset();
        form.elements.language.value = 'English';
        form.elements.id.readOnly = false;
        form.querySelector('[type="submit"]').textContent = 'Add resource';
        await load();
      } catch (error) { note(form.querySelector('.form-message'), error.message); }
    });
    load().catch(error => note(form.querySelector('.form-message'), error.message));
  }

  function renderReturnRequests(root, requests) {
    const box = section('Pending return approvals');
    const refresh = el('button', 'secondary-button return-refresh', 'Refresh requests');
    refresh.type = 'button';
    refresh.addEventListener('click', () => loadAccount());
    box.append(refresh);
    if (!requests.length) box.append(el('p', 'muted', 'No return requests are waiting for approval.'));
    for (const request of requests) {
      const row = el('div', 'loan-row'), info = el('div');
      const fine = Number(request.fine) || 0;
      const outstanding = Number(request.fineOutstanding ?? (fine - Number(request.finePaid || 0) - Number(request.fineCancelled || 0))) || 0;
      const canceled = Number(request.fineCancelled) || 0;
      const fineStatus = fine > 0 ? (outstanding > 0 ? ` · overdue fine ₹${fine} (${outstanding} unpaid)` : request.finePaid > 0 ? ` · overdue fine ₹${fine} · paid` : canceled > 0 ? ` · overdue fine ₹${fine} · canceled` : ` · overdue fine ₹${fine}`) : ' · no overdue fine';
      info.append(el('b', '', `${request.title} · ${request.memberEmail}`), el('small', '', `Requested ${request.requestedAt} · due ${request.dueDate}${fineStatus}`));
      if (outstanding > 0) {
        const paid = el('button', 'secondary-button', `Mark ₹${outstanding} paid`);
        paid.addEventListener('click', async () => {
          paid.disabled = true;
          try {
            const result = await api('/api/staff/fines/paid', { method: 'POST', body: JSON.stringify({ loanId: request.loanId }) });
            note(status, result.message, true);
            await loadAccount();
          } catch (error) { paid.disabled = false; note(status, error.message); }
        });
        row.append(info, paid);
      } else {
        row.append(info);
      }
      const approve = el('button', 'reserve-button', 'Approve return');
      approve.disabled = outstanding > 0;
      if (outstanding > 0) {
        approve.textContent = 'Pay fine before approval';
        approve.title = 'Mark the overdue fine as paid before approving this return.';
      }
      approve.addEventListener('click', async () => {
        approve.disabled = true;
        try { const result = await api(`/api/staff/returns/${encodeURIComponent(request.id)}`, { method: 'POST', body: JSON.stringify({ action: 'approve' }) }); note(status, result.message, true); await loadAccount(); }
        catch (error) { note(status, error.message); await loadAccount(); }
      });
      const reject = el('button', 'secondary-button', 'Reject');
      reject.addEventListener('click', async () => {
        reject.disabled = true;
        try { const result = await api(`/api/staff/returns/${encodeURIComponent(request.id)}`, { method: 'POST', body: JSON.stringify({ action: 'reject' }) }); note(status, result.message, true); await loadAccount(); }
        catch (error) { reject.disabled = false; note(status, error.message); }
      });
      row.append(approve, reject);
      box.append(row);
    }
    root.append(box);
  }
  async function renderLibrarian(root, page = 'overview') {
    if (page === 'loans') {
      const data = await api('/api/staff/loans');
      renderAdminLoans(root, { loans: data.items || [] }, false);
      return;
    }
    if (page === 'returns') {
      root.append(el('p', 'muted', 'Review member return requests. An item remains issued until a staff member approves it.'));
      const returns = await api('/api/staff/returns');
      renderReturnRequests(root, returns.items);
      return;
    }
    const intro = el('p', 'muted', 'Approve or reject return requests, and add, remove, or edit catalogue resources and copy counts.');
    root.append(intro);
    const returns = await api('/api/staff/returns');
    renderReturnRequests(root, returns.items);
    resourceManager(root);
  }

  function renderContentManager(root, kind, items) {
    const isEvent = kind === 'events';
    const title = isEvent ? 'Homepage events' : 'Homepage announcements';
    const box = section(title);
    const form = el('form', 'catalog-form');
    form.innerHTML = isEvent
      ? '<label>Add or edit an event (the homepage displays up to three)</label><input name="id" type="hidden"><div class="resource-fields"><input name="date" required placeholder="Date, e.g. 05 Nov"><input name="title" required placeholder="Event title"><input name="time" required placeholder="Time"><input name="location" required placeholder="Location"><button type="submit">Add event</button></div><p class="form-message" role="status"></p>'
      : '<label>Add or edit an announcement (the homepage displays up to three)</label><input name="id" type="hidden"><div class="resource-fields"><input name="date" required placeholder="Date, e.g. 28 Oct"><input name="title" required placeholder="Headline"><textarea name="description" required placeholder="Short description"></textarea><button type="submit">Add announcement</button></div><p class="form-message" role="status"></p>';
    const list = el('div', 'content-admin-list');
    for (const item of items) {
      const row = el('div', 'loan-row');
      const info = el('div');
      info.append(el('b', '', `${item.date} · ${item.title}`), el('small', '', isEvent ? `${item.time} · ${item.location}` : item.description));
      const edit = el('button', 'secondary-button', 'Edit');
      edit.addEventListener('click', () => {
        form.elements.id.value = item.id;
        for (const key of isEvent ? ['date', 'title', 'time', 'location'] : ['date', 'title', 'description']) form.elements[key].value = item[key];
        form.querySelector('[type="submit"]').textContent = isEvent ? 'Save event' : 'Save announcement';
        form.scrollIntoView({ behavior: 'smooth', block: 'center' });
      });
      const remove = el('button', 'secondary-button', 'Remove');
      remove.addEventListener('click', async () => {
        try { await api(`/api/admin/${kind}/${encodeURIComponent(item.id)}`, { method: 'DELETE' }); await loadAccount(); }
        catch (error) { note(status, error.message); }
      });
      row.append(info, edit, remove);
      list.append(row);
    }
    form.addEventListener('submit', async event => {
      event.preventDefault();
      try {
        const data = Object.fromEntries(new FormData(form));
        const id = data.id;
        const result = await api(id ? `/api/admin/${kind}/${encodeURIComponent(id)}` : `/api/admin/${kind}`, { method: id ? 'PUT' : 'POST', body: JSON.stringify(data) });
        note(form.querySelector('.form-message'), result.message, true);
        form.reset();
        form.querySelector('[type="submit"]').textContent = isEvent ? 'Add event' : 'Add announcement';
        await loadAccount();
      } catch (error) { note(form.querySelector('.form-message'), error.message); }
    });
    box.append(form, list);
    root.append(box);
  }

  function adminPageLinks() {
    return [
      ['admin.html', 'Overview', 'overview'],
      ['admin-members.html', 'Membership & Members', 'members'],
      ['admin-complaints.html', 'Complaints', 'complaints'],
      ['admin-loans.html', 'Loans & Fines', 'loans'],
      ['admin-returns.html', 'Return Approvals', 'returns'],
      ['admin-content.html', 'Homepage Content', 'content'],
      ['admin-resources.html', 'Resource Management', 'resources'],
    ];
  }
  function renderAdminOverview(root, data) {
    const intro = el('p', 'muted', 'Choose a section to manage the library. Changes are saved in the connected MySQL database.');
    root.append(intro);
    const tiles = el('div', 'admin-tile-grid');
    const openCount = data.complaints.filter(c => c.status !== 'resolved').length;
    const activeLoans = data.loans.filter(l => l.status === 'issued').length;
    const cards = [
      ['admin-members.html', 'Membership & Members', `${data.requests.length} pending registrations · ${data.users.filter(u => u.role === 'member').length} members`],
      ['admin-complaints.html', 'Complaints', `${openCount} open · ${data.complaints.length} total`],
      ['admin-loans.html', 'Loans & Fines', `${activeLoans} active loans`],
      ['admin-returns.html', 'Return Approvals', `${(data.returnRequests || []).length} pending requests`],
      ['admin-content.html', 'Homepage Content', 'Edit announcements and events'],
      ['admin-resources.html', 'Resource Management', 'Add, update, or remove resources'],
    ];
    for (const [href, title, summary] of cards) {
      const link = el('a', 'admin-tile');
      link.href = href;
      link.append(el('strong', '', title), el('span', '', summary), el('b', '', 'Open section →'));
      tiles.append(link);
    }
    root.append(tiles);
  }
  function renderAdminMembers(root, data) {
    const requests = section('Membership requests');
    if (!data.requests.length) requests.append(el('p', 'muted', 'No pending registrations.'));
    for (const request of data.requests) {
      const row = el('div', 'loan-row'), info = el('div');
      info.append(el('b', '', request.name), el('small', '', `${request.email} · requested ${request.date}`));
      row.append(info);
      for (const action of ['approve', 'reject']) {
        const button = el('button', action === 'approve' ? 'reserve-button' : 'secondary-button', action[0].toUpperCase() + action.slice(1));
        button.addEventListener('click', async () => {
          button.disabled = true;
          try { const result = await api('/api/admin/requests', { method: 'POST', body: JSON.stringify({ email: request.email, action }) }); note(status, result.message, true); await loadAccount(); }
          catch (error) { button.disabled = false; note(status, error.message); }
        });
        row.append(button);
      }
      requests.append(row);
    }
    root.append(requests);
    const members = section('Member accounts');
    const add = el('form', 'catalog-form');
    add.innerHTML = '<label>Add a member</label><div class="resource-fields"><input name="name" required placeholder="Full name"><input name="email" type="email" required placeholder="Email"><input name="password" type="password" minlength="8" required placeholder="Temporary password"><button type="submit">Add member</button></div><p class="form-message" role="status"></p>';
    members.append(add);
    for (const user of data.users.filter(entry => entry.role === 'member')) {
      const row = el('div', 'loan-row'), info = el('div');
      info.append(el('b', '', user.name), el('small', '', user.email));
      const remove = el('button', 'secondary-button', 'Remove member');
      remove.addEventListener('click', async () => {
        if (!confirm(`Remove member ${user.email}?`)) return;
        remove.disabled = true;
        try { await api(`/api/admin/members/${encodeURIComponent(user.email)}`, { method: 'DELETE' }); await loadAccount(); }
        catch (error) { remove.disabled = false; note(status, error.message); }
      });
      row.append(info, remove); members.append(row);
    }
    add.addEventListener('submit', async event => {
      event.preventDefault(); const button = add.querySelector('[type="submit"]'); button.disabled = true;
      try { const result = await api('/api/admin/members', { method: 'POST', body: JSON.stringify(Object.fromEntries(new FormData(add))) }); note(add.querySelector('.form-message'), result.message || 'Member added.', true); add.reset(); await loadAccount(); }
      catch (error) { note(add.querySelector('.form-message'), error.message); }
      finally { button.disabled = false; }
    });
    root.append(members);
  }
  function renderAdminComplaints(root, data) {
    const complaints = section('Contact messages and complaints');
    if (!data.complaints.length) complaints.append(el('p', 'muted', 'No contact messages received yet. Messages submitted through Contact Us will appear here.'));
    for (const complaint of data.complaints) {
      const row = el('article', 'book-card'), info = el('div');
      info.append(el('b', '', `${complaint.name} · ${complaint.email} · ${complaint.date}`), el('p', '', complaint.message), el('small', '', `Status: ${complaint.status}`));
      const toggle = el('button', 'secondary-button', complaint.status === 'resolved' ? 'Reopen' : 'Mark resolved');
      toggle.addEventListener('click', async () => {
        toggle.disabled = true;
        try { const result = await api(`/api/admin/complaints/${encodeURIComponent(complaint.id)}`, { method: 'POST', body: JSON.stringify({ status: complaint.status === 'resolved' ? 'open' : 'resolved' }) }); if (!result.ok) throw new Error('Complaint status was not changed.'); await loadAccount(); }
        catch (error) { toggle.disabled = false; note(status, error.message); }
      });
      row.append(info, toggle); complaints.append(row);
    }
    root.append(complaints);
  }
  function renderAdminLoans(root, data, showReturns = true) {
    if (showReturns) renderReturnRequests(root, data.returnRequests || []);
    const loans = section('Borrowing history and fine tracking');
    const records = [...(data.loans || [])].sort((a, b) => String(b.issued || '').localeCompare(String(a.issued || '')));
    const active = records.filter(loan => loan.status === 'issued').length;
    const overdue = records.filter(loan => loan.status === 'issued' && Number(loan.fine) > 0).length;
    const paidFine = records.reduce((sum, loan) => sum + (Number(loan.finePaid) || 0), 0);
    const summary = el('div', 'loan-tracker-summary');
    for (const [label, value] of [
      ['All loan records', records.length],
      ['Currently borrowed', active],
      ['Overdue', overdue],
      ['Fine collected till date', `₹${paidFine}`],
    ]) {
      const stat = el('div', 'loan-tracker-stat');
      stat.append(el('span', '', label), el('strong', '', String(value)));
      summary.append(stat);
    }
    loans.append(summary);

    const controls = el('div', 'loan-tracker-controls');
    const search = el('input', 'loan-tracker-search');
    search.type = 'search';
    search.placeholder = 'Search member, resource, or loan ID';
    search.setAttribute('aria-label', 'Search loan history');
    const filter = el('select', 'loan-tracker-filter');
    filter.setAttribute('aria-label', 'Filter loan history');
    for (const [value, label] of [['all', 'All loans'], ['issued', 'Currently borrowed'], ['overdue', 'Overdue'], ['returned', 'Returned'], ['fine', 'Fine not yet charged']]) {
      const option = el('option', '', label);
      option.value = value;
      filter.append(option);
    }
    controls.append(search, filter);
    loans.append(controls);

    const tableWrap = el('div', 'loan-tracker-table-wrap');
    const table = el('table', 'loan-tracker-table');
    const head = el('thead');
    const headerRow = el('tr');
    for (const label of ['Borrower', 'Resource', 'Borrowed on', 'Due date', 'Status', 'Fine', 'Action']) headerRow.append(el('th', '', label));
    head.append(headerRow);
    const body = el('tbody');
    table.append(head, body);
    tableWrap.append(table);
    loans.append(tableWrap);

    const renderRows = () => {
      body.replaceChildren();
      const query = search.value.trim().toLowerCase();
      const filtered = records.filter(loan => {
        const fine = Number(loan.fine) || 0;
        const isOverdue = loan.status === 'issued' && fine > 0;
        const matchesFilter = filter.value === 'all'
          || filter.value === 'issued' && loan.status === 'issued'
          || filter.value === 'overdue' && isOverdue
          || filter.value === 'returned' && loan.status === 'returned'
          || filter.value === 'fine' && fine > 0 && !loan.fineCharged;
        const searchable = `${loan.memberName || ''} ${loan.email || ''} ${loan.title || ''} ${loan.bookId || ''} ${loan.id || ''}`.toLowerCase();
        return matchesFilter && (!query || searchable.includes(query));
      });
      if (!filtered.length) {
        const row = el('tr');
        const cell = el('td', 'loan-tracker-empty', records.length ? 'No loans match this search or filter.' : 'No borrowing records yet. Issued resources will appear here.');
        cell.colSpan = 7;
        row.append(cell);
        body.append(row);
        return;
      }
      for (const loan of filtered) {
        const fine = Number(loan.fine) || 0;
        const isOverdue = loan.status === 'issued' && fine > 0;
        const row = el('tr');
        const borrower = el('td');
        borrower.append(el('strong', '', loan.memberName || loan.email || 'Unknown member'), el('small', 'loan-tracker-subtext', loan.email || ''));
        const resource = el('td');
        resource.append(el('strong', '', loan.title || 'Unknown resource'), el('small', 'loan-tracker-subtext', `ID: ${loan.bookId || '—'} · Loan: ${loan.id || '—'}`));
        const statusCell = el('td');
        statusCell.append(el('span', `loan-status${isOverdue ? ' overdue' : loan.status === 'returned' ? ' returned' : ''}`, isOverdue ? 'Overdue' : loan.status === 'returned' ? 'Returned' : 'Borrowed'));
        if (loan.returnPending) statusCell.append(el('small', 'loan-tracker-subtext', 'Return requested'));
        const fineCell = el('td');
        fineCell.append(el('strong', fine > 0 ? 'loan-fine' : 'loan-no-fine', fine > 0 ? `₹${fine}` : 'No fine'));
        const finePaid = Number(loan.finePaid) || 0;
        const fineCancelled = Number(loan.fineCancelled) || 0;
        const fineOutstanding = Number(loan.fineOutstanding ?? Math.max(0, fine - finePaid - fineCancelled)) || 0;
        if (fine > 0) {
          const fineState = fineOutstanding > 0
            ? `₹${fineOutstanding} unpaid${finePaid > 0 ? ` · ₹${finePaid} paid` : ''}${fineCancelled > 0 ? ` · ₹${fineCancelled} canceled` : ''}`
            : finePaid > 0 ? `₹${finePaid} paid${fineCancelled > 0 ? ` · ₹${fineCancelled} canceled` : ''}`
              : fineCancelled > 0 ? `₹${fineCancelled} canceled` : loan.fineCharged ? 'Charge recorded' : 'Not charged';
          fineCell.append(el('small', 'loan-tracker-subtext', fineState));
        }
        const actionCell = el('td');
        if (fine > 0 && !loan.fineCharged) {
          const charge = el('button', 'reserve-button', `Charge ₹${fine}`);
          charge.addEventListener('click', async () => {
            charge.disabled = true;
            try {
              const result = await api('/api/staff/fines', { method: 'POST', body: JSON.stringify({ loanId: loan.id }) });
              note(status, result.message, true);
              await loadAccount();
            } catch (error) {
              charge.disabled = false;
              note(status, error.message);
            }
          });
          actionCell.append(charge);
        }
        const manual = el('button', 'secondary-button', 'Add manual fine');
        manual.addEventListener('click', () => {
          const amount = el('input', 'loan-tracker-search');
          amount.type = 'number';
          amount.min = '1';
          amount.max = '500000';
          amount.step = '1';
          amount.placeholder = 'Amount (₹)';
          amount.setAttribute('aria-label', `Manual fine amount for ${loan.memberName || loan.email}`);
          const save = el('button', 'reserve-button', 'Apply fine');
          const cancel = el('button', 'secondary-button', 'Cancel');
          const editor = el('div', 'loan-fine-editor');
          editor.append(amount, save, cancel);
          actionCell.replaceChildren(editor);
          amount.focus();
          cancel.addEventListener('click', renderRows);
          save.addEventListener('click', async () => {
            const fineAmount = Number(amount.value);
            if (!Number.isInteger(fineAmount) || fineAmount < 1 || fineAmount > 500000) {
              note(status, 'Enter a whole amount between ₹1 and ₹500,000.');
              amount.focus();
              return;
            }
            save.disabled = true;
            try {
              const result = await api('/api/staff/fines', { method: 'POST', body: JSON.stringify({ loanId: loan.id, amount: String(fineAmount) }) });
              note(status, result.message, true);
              await loadAccount();
            } catch (error) {
              save.disabled = false;
              note(status, error.message);
            }
          });
        });
        actionCell.append(manual);
        if (fineOutstanding > 0) {
          const cancel = el('button', 'secondary-button', 'Cancel fine');
          cancel.addEventListener('click', async () => {
            if (!window.confirm(`Cancel the unpaid fine of ₹${fineOutstanding} for ${loan.memberName || loan.email}?`)) return;
            cancel.disabled = true;
            try {
              const result = await api('/api/staff/fines/cancel', { method: 'POST', body: JSON.stringify({ loanId: loan.id }) });
              note(status, result.message, true);
              await loadAccount();
            } catch (error) {
              cancel.disabled = false;
              note(status, error.message);
            }
          });
          actionCell.append(cancel);
        }
        row.append(borrower, resource, el('td', '', loan.issued || '—'), el('td', '', loan.dueDate || '—'), statusCell, fineCell, actionCell);
        body.append(row);
      }
    };
    search.addEventListener('input', renderRows);
    filter.addEventListener('change', renderRows);
    renderRows();
    root.append(loans);
  }
  async function renderAdmin(root, data, page) {
    if (page === 'overview') { renderAdminOverview(root, data); return; }
    if (page === 'members') { renderAdminMembers(root, data); return; }
    if (page === 'complaints') { renderAdminComplaints(root, data); return; }
    if (page === 'loans') { renderAdminLoans(root, data); return; }
    if (page === 'returns') { renderReturnRequests(root, data.returnRequests || []); return; }
    if (page === 'content') { renderContentManager(root, 'announcements', data.announcements || []); renderContentManager(root, 'events', data.events || []); return; }
    if (page === 'resources') { resourceManager(root); return; }
    throw new Error(`Unknown Admin section: ${page}`);
  }

  if (login) login.addEventListener('submit', async event => {
    event.preventDefault();
    const button = login.querySelector('[type="submit"]');
    button.disabled = true;
    try {
      const fields = new FormData(login);
      const data = await api('/api/login', { method: 'POST', body: JSON.stringify({ email: fields.get('email'), password: fields.get('password'), role: fields.get('role') }) });
      location.href = `${data.user.role}.html`;
    } catch (error) { note(status, error.message); }
    finally { button.disabled = false; }
  });
  if (panel) loadAccount();

  const contact = q('#contact-form');
  if (contact) contact.addEventListener('submit', async event => {
    event.preventDefault();
    try {
      const data = await api('/api/contact', { method: 'POST', body: JSON.stringify(Object.fromEntries(new FormData(contact))) });
      note(q('#contact-message'), data.message, true);
      contact.reset();
    } catch (error) { note(q('#contact-message'), error.message); }
  });

  const announcements = q('.announcements');
  if (announcements) api('/api/announcements').then(data => {
    const nodes = announcements.querySelectorAll('.notice');
    nodes.forEach((node, i) => {
      const item = data.items[i];
      node.style.display = item ? '' : 'none';
      if (!item) return;
      const [day, month] = item.date.split(' ');
      node.querySelector('time').replaceChildren(el('b', '', day), el('small', '', month || ''));
      node.querySelector('span').replaceChildren(el('strong', '', item.title), el('small', '', item.description));
    });
  }).catch(() => { });
  const events = q('.events');
  if (events) api('/api/events').then(data => {
    const nodes = events.querySelectorAll('.notice');
    nodes.forEach((node, i) => {
      const item = data.items[i];
      node.style.display = item ? '' : 'none';
      if (!item) return;
      const [day, month] = item.date.split(' ');
      node.querySelector('time').replaceChildren(el('b', '', day), el('small', '', month || ''));
      node.querySelector('span').replaceChildren(el('strong', '', item.title), el('small', '', `${item.time} · ${item.location}`));
    });
  }).catch(() => { });

  const newsAnnouncements = q('#news-announcements');
  if (newsAnnouncements) api('/api/announcements').then(data => {
    const nodes = newsAnnouncements.querySelectorAll('.update-row');
    nodes.forEach((node, i) => {
      const item = data.items[i];
      node.style.display = item ? '' : 'none';
      if (!item) return;
      const [day, month] = item.date.split(' ');
      node.querySelector('time').replaceChildren(el('span', '', day), el('small', '', month || ''));
      node.querySelector('div').replaceChildren(el('b', '', item.title), el('p', '', item.description));
    });
  }).catch(() => { });
  const newsEvents = q('#news-events');
  if (newsEvents) api('/api/events').then(data => {
    const nodes = newsEvents.querySelectorAll('article');
    nodes.forEach((node, i) => {
      const item = data.items[i];
      node.style.display = item ? '' : 'none';
      if (!item) return;
      const [day, month] = item.date.split(' ');
      node.querySelector('time').replaceChildren(el('span', '', day), el('small', '', month || ''));
      node.querySelector('div').replaceChildren(el('b', '', item.title), el('p', '', `${item.time} · ${item.location}`));
    });
  }).catch(() => { });
})();
