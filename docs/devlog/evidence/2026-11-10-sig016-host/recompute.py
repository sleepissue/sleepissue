"""기기 조작 없이 보존 원본 네 실행의 경계/비교표를 다시 계산한다."""
import json
import runpy
from pathlib import Path
ROOT=Path(__file__).resolve().parent
source=ROOT/'analyze-boundaries.py'
namespace={'__file__':str(source),'__name__':'boundary_analysis'}
exec(compile(source.read_text(),str(source),'exec'),namespace)
for phase in ['current1','current2','low1','low2']:
    namespace['ROOT']=ROOT/phase
    (ROOT/phase/'boundary-summary.json').write_text(json.dumps(namespace['analyze'](),ensure_ascii=False,indent=2)+'\n')
runpy.run_path(str(ROOT/'summarize.py'),run_name='__main__')
