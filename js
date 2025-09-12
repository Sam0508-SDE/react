<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <title>Advanced Todo – DOMSutra</title>
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <style>
    :root { --gap: 10px; }
    body { font-family: system-ui, -apple-system, Segoe UI, Roboto, Arial; max-width: 720px; margin: 40px auto; padding: 0 16px; }
    h1 { margin: 0 0 16px; }
    .row { display: flex; gap: var(--gap); }
    input[type="text"] { flex: 1; padding: 12px; font-size: 16px; }
    button { padding: 12px 14px; cursor: pointer; }
    ul { list-style: none; padding: 0; margin: 16px 0; }
    li { display: grid; grid-template-columns: 28px 1fr auto; align-items: center;
         gap: var(--gap); padding: 10px; border: 1px solid #e3e3e3; border-radius: 8px; margin-bottom: 10px; }
    .text { overflow-wrap: anywhere; }
    .done .text { text-decoration: line-through; opacity: .7; }
    .actions { display: flex; gap: 6px; }
    .icon { border: none; background: transparent; font-size: 18px; padding: 6px; }
    .filters { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .filters button { padding: 8px 10px; }
    .filters .active { outline: 2px solid #222; }
    .muted { color: #666; font-size: 14px; }
    .edit-input { width: 100%; padding: 8px; font-size: 16px; }
    .sr { position: absolute; left: -9999px; }
  </style>
</head>
<body>
  <h1>✅ Your Todos</h1>




****************************************************************************************************************


(function () {
  // --- State & Persistence ---
  const STORAGE_KEY = "domsutra.todos.v1";
  /** @type {{id:string,text:string,done:boolean}[]} */
  let todos = JSON.parse(localStorage.getItem(STORAGE_KEY) || "[]");
  let filter = "all"; // all | active | completed

  const $ = (sel) => document.querySelector(sel);
  const input = $("#todoInput");
  const addBtn = $("#addBtn");
  const list = $("#todoList");
  const leftCount = $("#leftCount");
  const clearCompletedBtn = $("#clearCompleted");
  const filterButtons = Array.from(document.querySelectorAll(".filter-btn"));

  function save() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(todos));
  }

  function uid() {
    return Math.random().toString(36).slice(2, 9);
  }

  // --- CRUD ---
  function addTodo(text) {
    todos.push({ id: uid(), text, done: false });
    save(); render();
  }

  function toggleTodo(id) {
    const t = todos.find(x => x.id === id);
    if (t) t.done = !t.done;
    save(); render();
  }

  function deleteTodo(id) {
    todos = todos.filter(x => x.id !== id);
    save(); render();
  }

  function updateTodo(id, newText) {
    const t = todos.find(x => x.id === id);
    if (t) t.text = newText;
    save(); render();
  }

  // --- UI Render ---
  function render() {
    list.innerHTML = "";
    const filtered = todos.filter(t =>
      filter === "active" ? !t.done :
      filter === "completed" ? t.done : true
    );

    filtered.forEach(t => {
      const li = document.createElement("li");
      if (t.done) li.classList.add("done");

      // checkbox
      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.checked = t.done;
      cb.ariaLabel = "Mark as done";
      cb.addEventListener("change", () => toggleTodo(t.id));

      // text / edit
      const text = document.createElement("span");
      text.className = "text";
      text.textContent = t.text;
      text.title = "Double-click to edit";
      text.addEventListener("dblclick", () => beginEdit(li, t));

      // actions
      const actions = document.createElement("div");
      actions.className = "actions";
      const editBtn = document.createElement("button");
      editBtn.className = "icon";
      editBtn.title = "Edit";
      editBtn.textContent = "✏️";
      editBtn.addEventListener("click", () => beginEdit(li, t));

      const delBtn = document.createElement("button");
      delBtn.className = "icon";
      delBtn.title = "Delete";
      delBtn.textContent = "❌";
      delBtn.addEventListener("click", () => deleteTodo(t.id));

      actions.appendChild(editBtn);
      actions.appendChild(delBtn);

      li.appendChild(cb);
      li.appendChild(text);
      li.appendChild(actions);
      list.appendChild(li);
    });

    const left = todos.filter(t => !t.done).length;
    leftCount.textContent = `${left} item${left === 1 ? "" : "s"} left`;
    toggleClearCompletedVisibility();
    highlightActiveFilter();
  }

  function beginEdit(li, todo) {
    // replace text span with input
    const current = li.querySelector(".text");
    const input = document.createElement("input");
    input.type = "text";
    input.className = "edit-input";
    input.value = todo.text;
    current.replaceWith(input);
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);

    const finish = (commit) => {
      const newVal = input.value.trim();
      if (commit && newVal) updateTodo(todo.id, newVal);
      else render(); // cancel or empty → revert
    };

    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter") finish(true);
      if (e.key === "Escape") finish(false);
    });
    input.addEventListener("blur", () => finish(true));
  }

  // --- Filters / Controls ---
  filterButtons.forEach(btn => {
    btn.addEventListener("click", () => {
      filter = btn.dataset.filter;
      render();
    });
  });

  function highlightActiveFilter() {
    filterButtons.forEach(b => b.classList.toggle("active", b.dataset.filter === filter));
  }

  function toggleClearCompletedVisibility() {
    const anyCompleted = todos.some(t => t.done);
    clearCompletedBtn.style.display = anyCompleted ? "inline-block" : "none";
  }

  clearCompletedBtn.addEventListener("click", () => {
    todos = todos.filter(t => !t.done);
    save(); render();
  });

  // --- Add handlers ---
  addBtn.addEventListener("click", () => {
    const text = input.value.trim();
    if (!text) { alert("Please enter a task"); return; }
    addTodo(text);
    input.value = "";
    input.focus();
  });

  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter") addBtn.click();
  });

  // First render
  render();
})();

  <div class="row" role="group" aria-label="Add todo">
    <label for="todoInput" class="sr">Add a task</label>
    <input id="todoInput" type="text" placeholder="Add a task and press Enter…" />
    <button id="addBtn" type="button">Add</button>
  </div>

  <ul id="todoList" aria-live="polite" aria-label="Todo list"></ul>

  <div class="filters">
    <span id="leftCount" class="muted">0 items left</span>
    <div style="flex:1"></div>
    <button data-filter="all" class="filter-btn active" type="button">All</button>
    <button data-filter="active" class="filter-btn" type="button">Active</button>
    <button data-filter="completed" class="filter-btn" type="button">Completed</button>
    <button id="clearCompleted" type="button" title="Remove all completed">Clear Completed</button>
  </div>

  <!-- Load external JS file (same folder) -->
  <script src="./todo.js"></script>
</body>
</html>
