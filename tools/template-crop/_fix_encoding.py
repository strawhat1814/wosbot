from pathlib import Path
import re

p = Path(__file__).with_name("TemplateCrop.ps1")
text = p.read_text(encoding="utf-8", errors="replace")
repls = {
    "\u03b2\u20ac\u201d": " - ",
    "\u03b2\u20ac\u201c": " - ",
    "\u03b2\u20ac\u0094": " - ",
    "\u2014": " - ",
    "\u2013": "-",
    "\u2018": "'",
    "\u2019": "'",
    "\u201c": '"',
    "\u201d": '"',
    "—": " - ",
    "–": "-",
}
for a, b in repls.items():
    text = text.replace(a, b)
# Catch remaining mojibake of em dash (latin1 misread of utf-8 emdash is often Ã¢â‚¬â€")
text = text.replace("Ã¢â‚¬â€", " - ")
text = text.replace("Ã¢â‚¬â€œ", "-")
bad = sorted(set(re.findall(r"[^\x00-\x7F]{1,12}", text)))
print("remaining non-ascii:", bad[:50])
p.write_text(text, encoding="utf-8", newline="\n")
print("ok", p)
