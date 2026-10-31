"""扫 PNG 里「某种颜色的文字」的实际墨迹范围，用来验证文字有没有被容器裁掉。

    python png_ink_scan.py [png 路径] [x 上限] [路径前缀]

默认 `pc/../shots/pc_ui.png`（即 UiProbe 的产出）与 x<199（左栏）。
**为什么不用 Pillow**：本机 managed Python 没装，标准库 zlib 手解 PNG 足够，
省一次装包。为什么要量：肉眼看截图判断「字被切了没有」会误判 ——
本工具实测过 stringWidth == 标签宽度 == 墨迹宽度，靠数字定性才靠谱。
"""
import struct, sys, zlib, os


def read_png(p):
    d = open(p, 'rb').read()
    i=8; w=h=None; idat=b''
    while i<len(d):
        ln=struct.unpack('>I',d[i:i+4])[0]; typ=d[i+4:i+8]; data=d[i+8:i+8+ln]; i+=12+ln
        if typ==b'IHDR':
            w,h=struct.unpack('>II',data[:8])
        elif typ==b'IDAT': idat+=data
        elif typ==b'IEND': break
    raw=zlib.decompress(idat); stride=w*3; out=bytearray(); prev=bytearray(stride); pos=0
    for y in range(h):
        f=raw[pos]; pos+=1; line=bytearray(raw[pos:pos+stride]); pos+=stride
        if f==1:
            for x in range(3,stride): line[x]=(line[x]+line[x-3])&255
        elif f==2:
            for x in range(stride): line[x]=(line[x]+prev[x])&255
        elif f==3:
            for x in range(stride):
                a=line[x-3] if x>=3 else 0; line[x]=(line[x]+((a+prev[x])>>1))&255
        elif f==4:
            for x in range(stride):
                a=line[x-3] if x>=3 else 0; b=prev[x]; c=prev[x-3] if x>=3 else 0
                pp=a+b-c; pa=abs(pp-a); pb=abs(pp-b); pc=abs(pp-c)
                pr=a if (pa<=pb and pa<=pc) else (b if pb<=pc else c)
                line[x]=(line[x]+pr)&255
        out+=line; prev=line
    return w,h,out

PNG = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), '..', '..', 'shots', 'pc_ui.png')
XMAX = int(sys.argv[2]) if len(sys.argv) > 2 else 199

w, h, px = read_png(PNG)
print('png=%s  %dx%d  扫描 x<%d' % (PNG, w, h, XMAX))
rows={}
for y in range(h):
    xs=[]
    base=y*w*3
    for x in range(0,XMAX):
        o=base+x*3; r,g,b=px[o],px[o+1],px[o+2]
        # IP_COLOR = (21,90,46)：绿字（R 明显低于 G），排除按钮灰/标题栏
        if r<110 and g>60 and g>r+35 and g>b+25: xs.append(x)
    if len(xs)>=3: rows[y]=(min(xs),max(xs))
bands=[]; cur=None
for y in sorted(rows):
    if cur and y-cur[1]<=2:
        cur[1]=y; cur[2]=min(cur[2],rows[y][0]); cur[3]=max(cur[3],rows[y][1])
    else:
        if cur: bands.append(cur)
        cur=[y,y,rows[y][0],rows[y][1]]
if cur: bands.append(cur)
for b in bands:
    print('y %3d-%3d  x=%3d..%3d  宽=%3d  %s'%(b[0],b[1],b[2],b[3],b[3]-b[2]+1,
          '← 贴到左栏右缘，疑似被切' if b[3]>=196 else ''))
