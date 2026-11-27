package com.nononsense.vla.share

import java.io.File
import java.net.URLEncoder
import java.util.Locale

internal object PhoneFileExplorer {
    fun encode(name: String) = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    fun imageType(name: String): String? = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        else -> null
    }

    private fun path(segments: List<String>) = if (segments.isEmpty()) "/"
        else segments.joinToString("/", prefix = "/", postfix = "/", transform = ::encode)

    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    fun page(segments: List<String>, entries: List<File>): String = buildString {
        val base = path(segments)
        append(HEAD)
        append("<header><h1>Phone files</h1><nav aria-label='Folder path'><a href='/'>Home</a>")
        for (index in segments.indices) {
            append(" <span aria-hidden='true'>›</span> <a href='${path(segments.take(index + 1))}'>${escape(segments[index])}</a>")
        }
        append("</nav></header><main data-folder='$base'>")
        if (segments.isNotEmpty()) {
            append("<div class='toolbar'><a class='control' href='${path(segments.dropLast(1))}'>↑ Up one folder</a>")
            append("<button id='choose'>Upload files</button><input id='files' type='file' multiple hidden>")
            append("<button id='new-folder'>New folder</button></div>")
        } else append("<p>Open a folder to browse photos and other shared files.</p>")
        append("<p id='message' role='status' hidden></p>")
        if (entries.isEmpty()) append("<p>No files in this folder.</p>")
        append("<ul>")
        for (file in entries.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })) {
            val folder = file.isDirectory
            val href = base + encode(file.name) + if (folder) "/" else ""
            val image = !folder && imageType(file.name) != null
            val label = escape(file.name)
            append("<li>")
            if (image) append("<button class='thumb' data-preview='$href' data-name='$label' aria-label='Preview $label'><img loading='lazy' alt='' src='$href?preview=1'></button>")
            else append("<span class='item-icon' aria-hidden='true'>${if (folder) "&#128193;" else "&#128196;"}</span>")
            if (folder) append("<a class='item-name' href='$href'>$label</a>")
            else if (image) append("<button class='item-name preview-name' data-preview='$href' data-name='$label'>$label</button>")
            else append("<span class='item-name'>$label</span>")
            append("<div class='actions'>")
            if (!folder) append("<a class='control' href='$href' download>Download</a>")
            if (segments.isNotEmpty()) append("<button data-delete='$href' data-name='$label'>Delete</button>")
            append("</div></li>")
        }
        append("</ul></main>")
        append(TAIL)
    }

    private const val HEAD = """
        <!doctype html><html lang="en"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1"><title>Phone files · NN VLA</title>
        <style>
        *{box-sizing:border-box}
        body{margin:0 auto;padding:24px;max-width:1000px;background:#0a0a0a;color:#ede9e1;font:16px system-ui,sans-serif}
        header{border-bottom:1px solid #38332e;padding-bottom:16px}
        h1{margin:0 0 12px;font-size:28px}p{color:#c2b9ad}
        a{color:#e0b77f}a:hover{text-decoration:underline}
        nav{display:flex;flex-wrap:wrap;gap:8px;overflow-wrap:anywhere}
        .control,button{display:inline-block;padding:8px 12px;border:1px solid #625440;border-radius:4px;color:inherit;background:#211e1a;text-decoration:none;font:inherit;cursor:pointer}
        .control:hover,button:hover{background:#3a3025}button:focus-visible,a:focus-visible{outline:2px solid #e0b77f}
        .toolbar,.actions{display:flex;flex-wrap:wrap;gap:8px}.toolbar{margin:16px 0}
        ul{list-style:none;padding:0;border:1px solid #38332e}
        li{display:flex;align-items:center;gap:12px;padding:8px 12px;background:#181818;border-bottom:1px solid #302b26}li:last-child{border-bottom:0}
        .item-name{flex:1;min-width:0;overflow-wrap:anywhere}
        .preview-name{background:none;border:0;padding:0;text-align:left;color:#e0b77f}
        .thumb,.item-icon{width:52px;height:42px;flex:none;display:grid;place-items:center;padding:0}
        .thumb img{max-width:100%;max-height:40px;object-fit:contain}
        dialog{width:min(850px,94vw);padding:16px;background:#181818;color:#ede9e1;border:1px solid #b08d57}
        dialog::backdrop{background:#000b}dialog header{display:flex;align-items:center;flex-wrap:wrap;gap:12px}
        #preview-title{margin-right:auto;overflow-wrap:anywhere}
        #photo{display:block;margin:auto;max-width:100%;max-height:70vh}
        @media(max-width:550px){body{padding:12px}li{flex-wrap:wrap}.actions{margin-left:64px}}
        </style></head><body>
    """

    private const val TAIL = """
        <dialog id="preview"><header><strong id="preview-title"></strong><a id="save" class="control" download>Download</a>
        <form method="dialog"><button>Close</button></form></header><p id="preview-error" hidden>Preview unavailable. You can still download the file.</p>
        <img id="photo" alt=""></dialog>
        <script>
        const byId = id => document.getElementById(id);
        const base = document.querySelector("main").dataset.folder;
        const status = byId("message");
        const dialog = byId("preview");
        const photo = byId("photo");
        function message(text) { status.hidden = false; status.textContent = text; }
        async function change(url, method, body) {
          const response = await fetch(url, {method, body});
          if (!response.ok) throw new Error(await response.text() || response.statusText);
        }
        function failed(error) { message(error.message || "The phone could not complete the operation."); }
        byId("choose")?.addEventListener("click", () => byId("files").click());
        byId("files")?.addEventListener("change", async event => {
          try {
            for (const file of event.target.files) {
              message("Uploading " + file.name + "…");
              await change(base + encodeURIComponent(file.name), "PUT", file);
            }
            location.reload();
          } catch (error) { failed(error); }
          finally { event.target.value = ""; }
        });
        byId("new-folder")?.addEventListener("click", async () => {
          const name = prompt("New folder name")?.trim();
          if (!name) return;
          if (name.includes("/") || name.includes(String.fromCharCode(92)) || name.startsWith(".")) return message("Choose a simple folder name.");
          try { await change(base + encodeURIComponent(name), "MKCOL"); location.reload(); } catch (error) { failed(error); }
        });
        document.querySelectorAll("[data-delete]").forEach(button => button.addEventListener("click", async () => {
          if (!confirm("Delete " + button.dataset.name + "?")) return;
          try { await change(button.dataset.delete, "DELETE"); location.reload(); } catch (error) { failed(error); }
        }));
        photo.addEventListener("error", () => { byId("preview-error").hidden = false; });
        dialog.addEventListener("close", () => { photo.removeAttribute("src"); });
        document.querySelectorAll("[data-preview]").forEach(button => button.addEventListener("click", () => {
          byId("preview-title").textContent = button.dataset.name;
          byId("preview-error").hidden = true;
          byId("save").href = button.dataset.preview;
          photo.alt = button.dataset.name;
          photo.src = button.dataset.preview + "?preview=1";
          dialog.showModal();
        }));
        </script></body></html>
    """
}
