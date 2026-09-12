from io import BytesIO
from zipfile import ZipFile, ZIP_DEFLATED

import pytest

from agentown_tframex_adapter.file_input import read_office_file, MAX_FILE_BYTES


def archive(parts):
    output = BytesIO()
    with ZipFile(output, 'w', ZIP_DEFLATED) as file:
        for name, content in parts.items():
            file.writestr(name, content)
    return output.getvalue()


def document(text='원문 사실'):
    return archive({'word/document.xml': '<document><body><p><t>' + text + '</t></p></body></document>'})


def test_docx_preserves_text_tabs_and_line_breaks():
    result = read_office_file(document('A</t><tab/><t>B</t><br/><t>C'), 'docx')
    assert result['text'] == 'A\tB\nC'
    assert '이미지' in result['warning']


def test_pptx_follows_presentation_order_not_filename_order():
    result = read_office_file(archive({
        'ppt/presentation.xml': '<p xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sldId r:id="second"/><sldId r:id="first"/></p>',
        'ppt/_rels/presentation.xml.rels': '<rels><r Id="first" Target="slides/slide1.xml"/><r Id="second" Target="slides/slide2.xml"/></rels>',
        'ppt/slides/slide1.xml': '<s><p><t>다음 내용</t></p></s>',
        'ppt/slides/slide2.xml': '<s><p><t>먼저 볼 내용</t></p></s>',
    }), 'pptx')
    assert result['text'] == '[슬라이드 1]\n먼저 볼 내용\n\n[슬라이드 2]\n다음 내용'


def test_xlsx_preserves_addresses_strings_zero_false_formula_and_cached_value():
    result = read_office_file(archive({
        'xl/workbook.xml': '<w xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheet name="검수" r:id="sheet"/></w>',
        'xl/_rels/workbook.xml.rels': '<rels><r Id="sheet" Target="/xl/worksheets/sheet1.xml"/></rels>',
        'xl/sharedStrings.xml': '<sst><si><t>원문</t><r><t> 보존</t></r></si></sst>',
        'xl/worksheets/sheet1.xml': '<sheet><row><c r="A1" t="s"><v>0</v></c><c r="B1"><v>0</v></c><c r="C1" t="b"><v>0</v></c><c r="D1"><f>SUM(B1:B2)</f><v>12</v></c><c r="E1" t="inlineStr"><is><t>한글</t></is></c></row></sheet>',
    }), 'xlsx')
    for expected in ['[시트 검수]', 'A1: "원문 보존"', 'B1: "0"', 'C1: "false"', 'SUM(B1:B2)', '"cachedValue": "12"', '재계산하지 않음', 'E1: "한글"']:
        assert expected in result['text']


@pytest.mark.parametrize('content,format', [
    (b'not a zip', 'docx'), (b'', 'docx'), (document(), 'ppt'),
    (b'x' * (MAX_FILE_BYTES + 1), 'xlsx'),
    (archive({'word/document.xml': '<!DOCTYPE x [<!ENTITY x "bad">]><p><t>&x;</t></p>'}), 'docx'),
    (archive({'word/document.xml': '<!DOCTYPE x><p/>'.encode('utf-16')}), 'docx'),
    (archive({'word/document.xml': '<invalid'}), 'docx'),
    (archive({'different.xml': '<p/>'}), 'docx'),
    (document(''), 'docx'), (document('x' * (128 * 1024 + 1)), 'docx'),
    (archive({'word/document.xml': b'x' * (32 * 1024 * 1024 + 1)}), 'docx'),
])
def test_invalid_files_fail_without_partial_success(content, format):
    with pytest.raises(ValueError):
        read_office_file(content, format)


def test_external_presentation_reference_is_not_followed():
    with pytest.raises(ValueError):
        read_office_file(archive({
            'ppt/presentation.xml': '<p xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sldId r:id="remote"/></p>',
            'ppt/_rels/presentation.xml.rels': '<rels><r Id="remote" TargetMode="External" Target="https://example.invalid/private"/></rels>',
        }), 'pptx')
