"""기존 APK/runner를 바꾸지 않고 2초 간격 자원 통계와 기기 진단을 수집한다."""
import datetime, hashlib, json, os, subprocess, sys, threading, time
from pathlib import Path
ROOT=Path(__file__).resolve().parent
ADB='/home/a/Android/Sdk/platform-tools/adb'
IDE=Path('/home/a/.cache/Google/AndroidStudio2026.2.1/log/idea.log')
GRADLE=Path('/home/a/.gradle/daemon/9.6.0/daemon-157994.out.log')

def read(path):
    try:return Path(path).read_text()
    except (OSError,ValueError):return ''
def kv(s):
    out={}
    for l in s.splitlines():
        a=l.replace(':',' ').split()
        if len(a)>1:
            try:out[a[0]]=int(a[1])
            except ValueError:pass
    return out

def process(pid):
    name=read(f'/proc/{pid}/comm').strip()
    if not name:return None
    role=None
    if name.startswith('qemu-system'):role='avd'
    elif name=='studio':role='studio'
    elif name=='java':
        cmd=read(f'/proc/{pid}/cmdline')
        for token,label in [('GradleDaemon','gradle_daemon'),('KotlinCompileDaemon','kotlin_daemon'),('GradleWrapperMain','gradle_client'),('GradleWorkerMain','build_worker'),('K2JVMCompiler','compiler_worker')]:
            if token in cmd:role=label;break
    elif any(s in name.lower() for s in ['perfetto','simpleperf','trace_processor']):role='extra_trace_process'
    if not role:return None
    st=read(f'/proc/{pid}/stat').rsplit(')',1)
    if len(st)!=2:return None
    v=st[1].split()
    if len(v)<22:return None
    status=kv(read(f'/proc/{pid}/status'))
    return {'pid':int(pid),'name':name,'role':role,'ticks':int(v[11])+int(v[12]),'startTicks':int(v[19]),
            'rssKiB':status.get('VmRSS'),'swapKiB':status.get('VmSwap'),'threads':status.get('Threads')}

def host():
    t=time.monotonic_ns(); ps=[]
    for d in Path('/proc').iterdir():
        if d.name.isdigit():
            row=process(d.name)
            if row:ps.append(row)
    stat=read('/proc/stat').splitlines()[0].split()
    return {'hostMonotonicNs':t,'wallUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'cpuTicks':list(map(int,stat[1:])), 'memory':kv(read('/proc/meminfo')),
            'vm':{k:v for k,v in kv(read('/proc/vmstat')).items() if k in ['pswpin','pswpout','pgmajfault']},
            'psi':{k:read('/proc/pressure/'+k).strip() for k in ['cpu','memory','io']},
            'load':read('/proc/loadavg').strip(),'cpu0KHz':read('/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq').strip(),
            'processes':ps}

def guest():
    before=time.monotonic_ns()
    cmd='cat /proc/uptime; head -n 5 /proc/stat; cat /proc/meminfo; cat /proc/pressure/cpu; cat /proc/pressure/memory'
    r=subprocess.run([ADB,'shell',cmd],capture_output=True,text=True,timeout=5)
    return {'hostBeforeNs':before,'hostAfterNs':time.monotonic_ns(),'returncode':r.returncode,'raw':r.stdout,'stderr':r.stderr}

def adb(args):
    r=subprocess.run([ADB]+args,capture_output=True,timeout=60)
    if r.returncode:raise RuntimeError((args,r.stderr.decode(errors='replace')))
    return r.stdout

def file_marker(f):return f.stat().st_size if f.exists() else 0

def appended(f,offset):
    if not f.exists():return ''
    with f.open('rb') as h:h.seek(offset);return h.read().decode(errors='replace')

def main(phase):
    out=ROOT/phase;out.mkdir(exist_ok=False)
    pre={str(p):file_marker(p) for p in [IDE,GRADLE]}
    (out/'services-before.txt').write_bytes(adb(['shell','ps','-A']))
    (out/'boot-id.txt').write_bytes(adb(['shell','cat','/proc/sys/kernel/random/boot_id']))
    (out/'navigation-start.txt').write_bytes(adb(['shell','am','start','-W','--user','10','-n','ai.umos.maps.android.navigation.app/.MainActivity']))
    run_id='sig016_host_'+phase+'_20261110'
    stop=threading.Event();hs=[];gs=[]
    def sample():
        index=0
        while not stop.is_set():
            hs.append(host())
            if index%2==0:
                try:gs.append(guest())
                except Exception as e:gs.append({'error':str(e)})
            index+=1;stop.wait(2)
    thread=threading.Thread(target=sample);thread.start()
    begin=time.monotonic_ns();wall=datetime.datetime.now(datetime.timezone.utc).isoformat()
    with (out/'runner.txt').open('wb') as log:
        r=subprocess.run([ADB,'shell','am','instrument','--user','10','-w','-e','role1Sig016','true','-e','role1Sig016Boundaries','true','-e','runId',run_id,'-e','class','com.sleepissue.awakedrive.data.Role1NaviDeadlineProbeTest','com.sleepissue.awakedrive.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT,timeout=150)
    end=time.monotonic_ns();stop.set();thread.join();hs.append(host())
    (out/'resources.json').write_text(json.dumps({'hostHz':os.sysconf('SC_CLK_TCK'),'hostCpuCount':os.cpu_count(),'sampling':'host every2sec, guest every4sec, + final host; no Perfetto recording','host':hs,'guest':gs},indent=2))
    (out/'device.json').write_bytes(adb(['exec-out','run-as','com.sleepissue.awakedrive','--user','10','cat',f'cache/role1-sig016-{run_id}.json']))
    (out/'metadata.json').write_text(json.dumps({'phase':phase,'runId':run_id,'hostBeginNs':begin,'hostEndNs':end,'wallBeginUtc':wall,'wallEndUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'runnerExitCode':r.returncode},indent=2))
    ide=appended(IDE,pre[str(IDE)]); ide_lines=[l for l in ide.splitlines() if any(x in l.lower() for x in ['index','dumb mode','scann'])]
    (out/'index-events.txt').write_text('\n'.join(ide_lines)+'\n')
    gl=appended(GRADLE,pre[str(GRADLE)]);build_lines=[l for l in gl.splitlines() if any(x in l for x in ['Executing build','Starting build','Build{id','Finished executing'])]
    (out/'build-events.txt').write_text('\n'.join(build_lines)+'\n')
    (out/'perfetto-after.txt').write_bytes(adb(['shell','perfetto','--query']))
    d=json.loads((out/'device.json').read_text())
    print(phase,'completed',d.get('completed'),'attempts',len(d.get('attempts',[])),'host samples',len(hs),'guest samples',len(gs),flush=True)
    assert d.get('completed') is True and len(d.get('attempts',[]))==3
    assert d.get('ownerAfterRelease') is None and d.get('leaseReleased') is True

if __name__=='__main__':main(sys.argv[1])
