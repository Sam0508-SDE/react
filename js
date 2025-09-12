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
