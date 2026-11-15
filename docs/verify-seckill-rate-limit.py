"""Run: python <this-file> <absolute-path-to-seckill-rate-limit.lua>.
Connects to localhost:16379, uses random independent keys, deletes only those keys.
"""
import socket, uuid, time, concurrent.futures, collections, pathlib, sys
script = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
prefix = 'codex:verify:ratelimit:{' + uuid.uuid4().hex + '}:'
keys = [prefix+'g', prefix+'u1', prefix+'seq', prefix+'u2', prefix+'cg', prefix+'cseq'] + [prefix+'cu:'+str(i) for i in range(12)] + [prefix+'sg',prefix+'su',prefix+'sseq',prefix+'mg',prefix+'mu',prefix+'mseq']
def parse(f):
    line = f.readline()
    if not line: raise RuntimeError('empty response')
    if line[:1] == b':': return int(line[1:-2])
    if line[:1] == b'+': return line[1:-2].decode()
    if line[:1] == b'-': raise RuntimeError(line[1:-2].decode())
    if line[:1] == b'$':
        n = int(line[1:-2])
        if n == -1: return None
        payload = f.read(n)
        if f.read(2) != b'\r\n': raise RuntimeError('invalid terminator')
        return payload.decode()
    if line[:1] == b'*': return [parse(f) for _ in range(int(line[1:-2]))]
    raise RuntimeError('unexpected response: '+repr(line))
def execute(*args):
    with socket.create_connection(('127.0.0.1',16379), timeout=10) as sock:
        sock.settimeout(10)
        data = [str(a).encode('utf-8') for a in args]
        msg = b'*'+str(len(data)).encode()+b'\r\n'+b''.join(b'$'+str(len(x)).encode()+b'\r\n'+x+b'\r\n' for x in data)
        sock.sendall(msg)
        return parse(sock.makefile('rb'))
def limit(g,u,seq,window,glimit,ulimit):
    return execute('EVAL',script,3,g,u,seq,window,glimit,ulimit)
try:
    g,u1,seq,u2,cg,cseq=keys[:6]
    res=[limit(g,u1,seq,1500,3,2) for _ in range(3)] + [limit(g,u2,seq,1500,3,2) for _ in range(2)]
    assert res == [1,1,2,1,0], res
    ttl=[execute('PTTL',k) for k in (g,u1,u2,seq)]
    assert all(0<n<=1500 for n in ttl), ttl
    time.sleep(1.7)
    assert limit(g,u1,seq,1500,3,2)==1
    print('thresholds, TTL, expiry OK:',res,ttl)
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        res=list(pool.map(lambda i: limit(cg,keys[6+i%12],cseq,10000,20,5),range(96)))
    counts=collections.Counter(res)
    assert counts[1]==20 and execute('ZCARD',cg)==20, counts
    print('global 96 concurrent:',dict(counts))
    sg,su,sseq,mg,mu,mseq=keys[-6:]
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        res=list(pool.map(lambda i: limit(sg,su,sseq,10000,100,5),range(96)))
    counts=collections.Counter(res)
    assert counts[1]==5 and counts[2]==91 and execute('ZCARD',sg)==5, counts
    print('one user 96 concurrent:',dict(counts))
    with concurrent.futures.ThreadPoolExecutor(max_workers=40) as pool:
        res=list(pool.map(lambda i: limit(mg,mu,mseq,10000,200,200),range(120)))
    assert all(x==1 for x in res) and execute('ZCARD',mg)==120 and execute('ZCARD',mu)==120
    scores=execute('ZRANGE',mg,0,-1,'WITHSCORES')[1::2]
    collisions=len(scores)-len(set(scores))
    print('120 allowed; distinct sorted-set members:',len(scores),'; same-ms timestamp collisions:',collisions)
    if collisions==0: print('NOTE: no identical-millisecond samples observed; same-ms case was not exercised')
finally:
    removed=execute('DEL',*keys)
    remaining=sum(execute('EXISTS',k) for k in keys)
    print('cleanup deleted:',removed,'remaining:',remaining)
    assert remaining==0
