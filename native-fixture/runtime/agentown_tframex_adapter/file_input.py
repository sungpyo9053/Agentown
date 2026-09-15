"""Bounded, local-only OOXML text import. Never extracts files or executes content."""
from io import BytesIO
import json
import posixpath
import xml.etree.ElementTree as ET
from zipfile import ZipFile, BadZipFile

MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_TEXT_BYTES = 128 * 1024
_REL = '{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id'


def _tag(element):
    return element.tag.rsplit('}', 1)[-1]


def _xml(parts, name):
    if name not in parts:
        raise ValueError('문서의 필수 구성 요소가 없습니다.')
    return ET.fromstring(parts[name])


def _paragraphs(root):
    return [''.join((node.text or '') if _tag(node) == 't' else '\t' if _tag(node) == 'tab' else '\n'
                   for node in paragraph.iter() if _tag(node) in {'t', 'tab', 'br', 'cr'})
            for paragraph in root.iter() if _tag(paragraph) == 'p']


def _ordered_parts(parts, main, item_tag):
    directory, filename = posixpath.split(main)
    relationships = _xml(parts, directory + '/_rels/' + filename + '.rels')
    targets = {}
    for rel in relationships:
        if rel.get('TargetMode') == 'External':
            continue  # Never follow URLs or external local files.
        target = rel.get('Target', '')
        path = posixpath.normpath(target.lstrip('/') if target.startswith('/') else directory + '/' + target)
        if path.startswith('../') or ':' in path:
            raise ValueError('안전하지 않은 문서 참조입니다.')
        targets[rel.get('Id')] = path
    for item in _xml(parts, main).iter():
        if _tag(item) == item_tag:
            target = targets.get(item.get(_REL))
            if not target:
                raise ValueError('외부 또는 누락된 문서 내용을 읽을 수 없습니다.')
            yield item, _xml(parts, target)


def _docx(parts):
    return '\n'.join(_paragraphs(_xml(parts, 'word/document.xml')))


def _pptx(parts):
    slides = list(_ordered_parts(parts, 'ppt/presentation.xml', 'sldId'))
    if not any(any(text.strip() for text in _paragraphs(root)) for _, root in slides):
        raise ValueError('읽을 수 있는 슬라이드 텍스트가 없습니다.')
    return '\n\n'.join(f'[슬라이드 {index}]\n' + '\n'.join(_paragraphs(root))
                       for index, (_, root) in enumerate(slides, 1))


def _xlsx(parts):
    strings = []
    if 'xl/sharedStrings.xml' in parts:
        strings = [''.join(n.text or '' for n in item.iter() if _tag(n) == 't')
                   for item in _xml(parts, 'xl/sharedStrings.xml')]
    result, cell_count = [], 0
    for sheet, root in _ordered_parts(parts, 'xl/workbook.xml', 'sheet'):
        result.append('[시트 ' + str(sheet.get('name', '이름 없음')) + ']')
        for cell in root.iter():
            if _tag(cell) != 'c':
                continue
            cell_count += 1
            children = {_tag(n): n for n in cell}
            value = children['v'].text or '' if 'v' in children else ''
            kind = cell.get('t')
            if kind == 's':
                index = int(value)
                if not 0 <= index < len(strings):
                    raise ValueError('셀의 공유 문자열 참조가 잘못되었습니다.')
                value = strings[index]
            elif kind == 'inlineStr':
                value = ''.join(n.text or '' for n in cell.iter() if _tag(n) == 't')
            elif kind == 'b':
                value = 'true' if value == '1' else 'false'
            if 'f' in children:
                value = {'formula': children['f'].text or '', 'cachedValue': value,
                         'calculation': '재계산하지 않음; 저장된 값은 오래되었을 수 있음'}
            result.append(str(cell.get('r', '?')) + ': ' + json.dumps(value, ensure_ascii=False))
    if not cell_count:
        raise ValueError('읽을 수 있는 셀이 없습니다.')
    return '\n'.join(result)


_READERS = {'docx': _docx, 'pptx': _pptx, 'xlsx': _xlsx}


def read_office_file(content: bytes, format: str) -> dict:
    if format not in _READERS or not 0 < len(content) <= MAX_FILE_BYTES:
        raise ValueError('8 MB 이하의 DOCX·PPTX·XLSX 파일을 선택해 주세요.')
    try:
        with ZipFile(BytesIO(content)) as archive:
            entries = archive.infolist()
            if len(entries) > 2000 or sum(e.file_size for e in entries) > 32 * 1024 * 1024:
                raise ValueError('압축을 푼 문서 크기가 안전 한도를 초과했습니다.')
            if len({e.filename for e in entries}) != len(entries) or any(e.flag_bits & 1 for e in entries):
                raise ValueError('중복 구성 요소 또는 암호화된 문서는 지원하지 않습니다.')
            parts = {}
            for entry in entries:
                if entry.filename.endswith(('.xml', '.rels')):
                    data = archive.read(entry)
                    # Reject DTD/entity declarations, including UTF-16 encoding.
                    normalized = data.replace(b'\x00', b'').upper()
                    if b'<!DOCTYPE' in normalized or b'<!ENTITY' in normalized:
                        raise ValueError('외부 개체 선언이 있는 문서는 읽지 않습니다.')
                    parts[entry.filename] = data
            text = _READERS[format](parts)
    except (BadZipFile, ET.ParseError, KeyError, IndexError, OverflowError, RuntimeError, NotImplementedError) as error:
        raise ValueError('손상되었거나 지원하지 않는 문서입니다. 기존 입력은 보존됩니다.') from error
    if not text.strip() or len(text.encode('utf-8')) > MAX_TEXT_BYTES:
        raise ValueError('읽을 수 있는 텍스트가 없거나 128 KB를 초과합니다. 임의로 잘라서 실행하지 않습니다.')
    warning = ('텍스트와 셀 내용만 읽었습니다. 이미지·도표의 시각 정보, 서식, 주석, 머리글·바닥글 및 발표자 노트는 검토하지 않았습니다. '
               '이미지 중심 자료는 원문을 별도로 확인하세요. 엑셀 날짜·숫자는 저장된 원시 값이며 수식은 재계산하지 않았습니다.')
    return {'text': text, 'warning': warning}
