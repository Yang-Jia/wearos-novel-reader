package com.wear.novelreader.transfer

import com.wear.novelreader.data.PublicBook

object UploadPage {

    fun render(books: List<PublicBook>): String {
        val bookRows = if (books.isEmpty()) {
            "<p class=\"empty\">还没有书，上传第一本吧。</p>"
        } else {
            books.joinToString("") { book ->
                "<div class=\"book\"><div class=\"book-title\">${escape(book.title)}</div>" +
                    "<div class=\"book-meta\">${escape(book.author.ifBlank { "未知作者" })} · " +
                    "${book.chapters} 章 · 已读 ${book.percent}%</div></div>"
            }
        }
        return """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>腕上书 · Wi-Fi 传书</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#0b0d10;color:#e7e9ee;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;line-height:1.5}
.wrap{max-width:720px;margin:0 auto;padding:28px 18px 48px}
h1{font-size:24px;margin:0 0 4px;letter-spacing:.5px}
.sub{margin:0 0 20px;color:#98a0ae;font-size:14px}
.drop{border:2px dashed #2d3748;border-radius:16px;padding:34px 18px;text-align:center;background:#11151b;transition:.18s;cursor:pointer}
.drop.over{border-color:#7fd1ff;background:#0f2631}
.drop strong{display:block;font-size:17px;color:#cfe8ff;margin-bottom:6px}
.drop span{color:#8b93a1;font-size:13px}
button{border:0;border-radius:10px;padding:11px 20px;font-size:15px;background:#7fd1ff;color:#06171f;font-weight:600;cursor:pointer}
button:disabled{opacity:.55;cursor:not-allowed}
.pick-row{margin:16px 0 22px;text-align:center}
.list{display:flex;flex-direction:column;gap:10px;margin-bottom:26px}
.file{background:#12171f;border:1px solid #1e2733;border-radius:12px;padding:12px 14px}
.file-head{display:flex;justify-content:space-between;gap:12px;font-size:14px}
.file-name{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.file-state{color:#8b93a1;font-size:12px;white-space:nowrap}
.bar{height:5px;background:#1e2733;border-radius:3px;margin-top:9px;overflow:hidden}
.bar-inner{height:100%;width:0;background:#7fd1ff;transition:width .15s}
.file.ok .file-state{color:#7ee2a8}.file.err .file-state{color:#ff8f8f}
h2{font-size:15px;color:#aab3c2;margin:0 0 12px;font-weight:600}
.books{display:flex;flex-direction:column;gap:8px}
.book{background:#101419;border:1px solid #1b232e;border-radius:10px;padding:11px 14px}
.book-title{font-size:14px;color:#e7e9ee;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.book-meta{font-size:12px;color:#8b93a1;margin-top:2px}
.empty{color:#6f7885;font-size:13px}
.tip{margin-top:24px;color:#6f7885;font-size:12px;border-top:1px solid #1b232e;padding-top:14px}
</style>
</head>
<body>
<div class="wrap">
<h1>腕上书</h1>
<p class="sub">Wi-Fi 传书 · 支持 TXT 与 EPUB，上传后自动导入书架</p>
<div id="drop" class="drop">
<strong>拖拽文件到这里</strong>
<span>或点击选择文件，可一次选择多本</span>
</div>
<div class="pick-row">
<button id="pick">选择文件</button>
<input id="fileInput" type="file" accept=".txt,.epub,text/plain,application/epub+zip" multiple hidden>
</div>
<div id="list" class="list"></div>
<h2>书架</h2>
<div id="books" class="books">$bookRows</div>
<p class="tip">请确保手表与电脑/手机连接同一 Wi-Fi。上传过程中请保持手表上的传书服务开启。</p>
</div>
<script>
var pick = document.getElementById('pick');
var input = document.getElementById('fileInput');
var drop = document.getElementById('drop');
var list = document.getElementById('list');
var books = document.getElementById('books');
var busy = false;

pick.onclick = function () { input.click(); };
drop.onclick = function () { input.click(); };
input.onchange = function () {
  if (input.files) { uploadAll(input.files); }
  input.value = '';
};
['dragenter','dragover'].forEach(function (name) {
  drop.addEventListener(name, function (event) {
    event.preventDefault();
    drop.classList.add('over');
  });
});
['dragleave','drop'].forEach(function (name) {
  drop.addEventListener(name, function (event) {
    event.preventDefault();
    drop.classList.remove('over');
  });
});
drop.addEventListener('drop', function (event) {
  if (event.dataTransfer && event.dataTransfer.files) {
    uploadAll(event.dataTransfer.files);
  }
});

function uploadAll(files) {
  var valid = [];
  for (var i = 0; i < files.length; i++) {
    var name = files[i].name.toLowerCase();
    if (name.endsWith('.txt') || name.endsWith('.epub')) {
      valid.push(files[i]);
    } else {
      addFile(files[i].name, '不支持的文件类型', true);
    }
  }
  if (!valid.length) { return; }
  busy = true;
  pick.disabled = true;
  var pending = valid.length;
  valid.forEach(function (file) {
    uploadOne(file, function () {
      pending--;
      if (pending === 0) {
        busy = false;
        pick.disabled = false;
        loadBooks();
      }
    });
  });
}

function uploadOne(file, done) {
  var row = addFile(file.name, '0%', false);
  var xhr = new XMLHttpRequest();
  xhr.open('POST', '/upload');
  xhr.upload.onprogress = function (event) {
    if (event.lengthComputable) {
      var percent = Math.round(event.loaded * 100 / event.total);
      row.state.textContent = percent + '%';
      row.bar.style.width = percent + '%';
    }
  };
  xhr.onload = function () {
    row.bar.style.width = '100%';
    var data = null;
    try { data = JSON.parse(xhr.responseText); } catch (e) {}
    if (xhr.status === 200 && data && data.ok) {
      var result = data.results && data.results[0];
      if (result && result.ok) {
        row.node.classList.add('ok');
        row.state.textContent = '已导入';
      } else {
        row.node.classList.add('err');
        row.state.textContent = result && result.message ? result.message : '导入失败';
      }
    } else {
      row.node.classList.add('err');
      row.state.textContent = data && data.message ? data.message : '上传失败';
    }
    done();
  };
  xhr.onerror = function () {
    row.node.classList.add('err');
    row.state.textContent = '网络错误';
    done();
  };
  var form = new FormData();
  form.append('file', file, file.name);
  xhr.send(form);
}

function addFile(name, state, error) {
  var node = document.createElement('div');
  node.className = 'file' + (error ? ' err' : '');
  var head = document.createElement('div');
  head.className = 'file-head';
  var nameNode = document.createElement('div');
  nameNode.className = 'file-name';
  nameNode.textContent = name;
  var stateNode = document.createElement('div');
  stateNode.className = 'file-state';
  stateNode.textContent = state;
  head.appendChild(nameNode);
  head.appendChild(stateNode);
  var bar = document.createElement('div');
  bar.className = 'bar';
  var inner = document.createElement('div');
  inner.className = 'bar-inner';
  bar.appendChild(inner);
  node.appendChild(head);
  node.appendChild(bar);
  list.appendChild(node);
  return { node: node, state: stateNode, bar: inner };
}

function loadBooks() {
  fetch('/api/books').then(function (response) {
    return response.json();
  }).then(function (data) {
    if (!data || !data.books) { return; }
    books.innerHTML = '';
    if (!data.books.length) {
      var empty = document.createElement('p');
      empty.className = 'empty';
      empty.textContent = '还没有书，上传第一本吧。';
      books.appendChild(empty);
      return;
    }
    data.books.forEach(function (book) {
      var node = document.createElement('div');
      node.className = 'book';
      var title = document.createElement('div');
      title.className = 'book-title';
      title.textContent = book.title;
      var meta = document.createElement('div');
      meta.className = 'book-meta';
      meta.textContent = (book.author || '未知作者') + ' · ' + book.chapters + ' 章 · 已读 ' + book.percent + '%';
      node.appendChild(title);
      node.appendChild(meta);
      books.appendChild(node);
    });
  }).catch(function () {});
}
</script>
</body>
</html>
""".trimIndent()
    }

    private fun escape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
