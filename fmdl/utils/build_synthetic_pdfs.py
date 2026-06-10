import os
import random
import string
from fpdf import FPDF
from PyPDF2 import PdfReader, PdfWriter
from PyPDF2.generic import NameObject, TextStringObject, DictionaryObject
from io import BytesIO

# Constants
TARGET_SIZE_MIN = 40 * 1024  # 40KB
TARGET_SIZE_MAX = 100 * 1024  # 100KB
NUM_SAMPLES_PER_CLASS = 100
OUTPUT_DIR = "data_syn_whole_100"


def random_digits(n: int) -> str:
    return ''.join(random.choices('0123456789', k=n))


def random_alphanum(n: int) -> str:
    return ''.join(random.choices(string.ascii_letters + string.digits, k=n))


def create_base_pdf(content_repeats=1) -> bytes:
    """Create a basic PDF with repeated text and return as bytes."""
    pdf = FPDF()
    pdf.add_page()
    pdf.set_font("Arial", size=12)
    for _ in range(content_repeats):
        pdf.multi_cell(0, 10, "This is a normal PDF. Nothing suspicious here.\n")
    pdf_string = pdf.output(dest='S').encode('latin1')
    return pdf_string


def pad_pdf_to_size(pdf_bytes: bytes, min_size: int, max_size: int, malicious: bool = False) -> bytes:
    """Pad a PDF to a specified size with either high entropy or benign data."""
    writer = PdfWriter()
    reader = PdfReader(BytesIO(pdf_bytes))

    for page in reader.pages:
        writer.add_page(page)

    current_size = len(pdf_bytes)
    target_size = random.randint(min_size, max_size)
    extra_size = max(0, target_size - current_size)

    if malicious:
        high_entropy_blob = os.urandom(extra_size)

        # Add fake JavaScript action
        fake_js = DictionaryObject()
        fake_js.update({
            NameObject("/S"): NameObject("/JavaScript"),
            NameObject("/JS"): TextStringObject("app.alert('Simulated malicious action');")
        })
        writer._root_object.update({
            NameObject("/OpenAction"): writer._add_object(fake_js)
        })

    else:
        dummy_text = "A" * extra_size
        writer.add_metadata({
            "/Title": dummy_text
        })

    output = BytesIO()
    writer.write(output)
    return output.getvalue()


def write_pdf_file(pdf_bytes: bytes, path: str):
    """Write the bytes to a file on disk (no .pdf extension)."""
    with open(path, "wb") as f:
        f.write(pdf_bytes)


def generate_pdfs(output_dir: str, num_per_class: int = 5):
    """Generate synthetic benign and malware-like PDFs."""
    benign_dir = os.path.join(output_dir, "benign")
    mware_dir = os.path.join(output_dir, "mware")
    os.makedirs(benign_dir, exist_ok=True)
    os.makedirs(mware_dir, exist_ok=True)

    print(f"[+] Generating {num_per_class} benign files...")
    for _ in range(num_per_class):
        base = create_base_pdf(content_repeats=random.randint(5, 20))
        padded = pad_pdf_to_size(base, TARGET_SIZE_MIN, TARGET_SIZE_MAX, malicious=False)
        filename = random_digits(7)  # e.g., 4721983
        filepath = os.path.join(benign_dir, filename)
        write_pdf_file(padded, filepath)

    print(f"[+] Generating {num_per_class} malware-like files...")
    for _ in range(num_per_class):
        base = create_base_pdf(content_repeats=random.randint(3, 10))
        padded = pad_pdf_to_size(base, TARGET_SIZE_MIN, TARGET_SIZE_MAX, malicious=True)
        filename = f"mw{random_alphanum(10)}{random_digits(random.randint(1, 4))}"  # e.g., mwK1dflffa023
        filepath = os.path.join(mware_dir, filename)
        write_pdf_file(padded, filepath)

    print("[✓] PDF generation complete.")


def main():
    generate_pdfs(OUTPUT_DIR, NUM_SAMPLES_PER_CLASS)


if __name__ == "__main__":
    main()
