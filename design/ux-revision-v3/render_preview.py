"""Deterministic UI concepts only; never edits the Android app."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json,math
ROOT=Path(__file__).resolve().parent
APP=ROOT.parents[1]
OUT=ROOT/'exports';OUT.mkdir(exist_ok=True)
S=2;W=432;H=960
BG='#101715';SURF='#19231F';RAISED='#23362E';LINE='#31453B';MINT='#8CE3C7';FG='#F1F5EF';MUTED='#A9B6AD';RED='#F1A3A3'
FONT=APP/'design/ui-preview-v1/assets/Inter-variable.ttf'
LOGO=Image.open(APP/'design/ui-preview-v2-logo/assets/swararia-logo-512.png').convert('RGBA')
FONTS={}
TRACKS=[('Cinta dan Airmata','fariz kendang - Topic','8:56'),('Soundcheck Kendang • Live Session','Gass Music','9:33'),('Denok feat. Ageng Music','Ageng Music','5:42'),('Instrumental Sore','Studio Selatan','4:18'),('Kendang Koplo • Bass Test','Faris Audio','6:24'),('Musik Pembuka Acara','Studio Selatan','3:50')]
SELECTED=[0,2,4]
def font(size=14,weight=400):
    key=(size,weight)
    if key not in FONTS:
        f=ImageFont.truetype(str(FONT),round(size*S));f.set_variation_by_axes([14,weight]);FONTS[key]=f
    return FONTS[key]
def box(x,y,w,h,fill=SURF,r=12,outline=None):
    d.rounded_rectangle(tuple(round(v*S) for v in [x,y,x+w,y+h]),radius=round(r*S),fill=fill,outline=outline,width=S)
def line(points,color=MUTED,width=1.7):
    d.line([(round(x*S),round(y*S)) for x,y in points],fill=color,width=round(width*S),joint='curve')
def text(x,y,value,size=14,color=FG,weight=400,maxw=None):
    f=font(size,weight)
    if maxw:
        while value and d.textlength(value,font=f)>maxw*S:value=value[:-2].rstrip()+'…'
    d.text((round(x*S),round(y*S)),value,font=f,fill=color,anchor='lt')
def centered(x,y,value,size=14,color=FG,weight=500):
    text(x-d.textlength(value,font=font(size,weight))/S/2,y,value,size,color,weight)
def wrapped(x,y,value,width,size=14,color=FG,weight=400,maxlines=2):
    words=value.split(); rows=[];row=''
    for word in words:
        candidate=(row+' '+word).strip()
        if d.textlength(candidate,font=font(size,weight))>width*S and row:rows.append(row);row=word
        else:row=candidate
    if row:rows.append(row)
    for i,part in enumerate(rows[:maxlines]):text(x,y+i*(size+5),part if i<maxlines-1 or len(rows)<=maxlines else part+'…',size,color,weight,maxw=width)
def icon(name,x,y,color=MUTED,size=22):
    # All icons share one stroke grid; x/y specify the centre.
    q=size/24
    def pts(items):return [(x+(a-12)*q,y+(b-12)*q) for a,b in items]
    def ln(items):line(pts(items),color,1.8*q)
    def ell(a,b,c,e):d.ellipse(tuple(round(v*S) for v in [x+(a-12)*q,y+(b-12)*q,x+(c-12)*q,y+(e-12)*q]),outline=color,width=max(1,round(1.8*q*S)))
    if name=='search':ell(3,3,17,17);ln([(15,15),(21,21)])
    elif name=='play':d.polygon([(round(a*S),round(b*S)) for a,b in pts([(8,5),(8,19),(19,12)])],fill=color)
    elif name=='pause':
        for xx in [8,15]:ln([(xx,5),(xx,19)])
    elif name=='download':ln([(12,3),(12,15)]);ln([(7,10),(12,15),(17,10)]);ln([(4,16),(4,21),(20,21),(20,16)])
    elif name=='back':ln([(15,5),(8,12),(15,19)]);ln([(8,12),(21,12)])
    elif name=='next':d.polygon([(round(a*S),round(b*S)) for a,b in pts([(5,5),(5,19),(16,12)])],fill=color);ln([(19,5),(19,19)])
    elif name=='prev':d.polygon([(round(a*S),round(b*S)) for a,b in pts([(19,5),(19,19),(8,12)])],fill=color);ln([(5,5),(5,19)])
    elif name=='check':ln([(5,12),(10,17),(20,6)])
    elif name=='close':ln([(6,6),(18,18)]);ln([(18,6),(6,18)])
    elif name=='chevron':ln([(7,9),(12,14),(17,9)])
    elif name=='more':
        for yy in [5,12,19]:box(x-1.3*q,y+(yy-12)*q-1.3*q,2.6*q,2.6*q,color,r=2)
    elif name=='sort':ln([(4,5),(20,5)]);ln([(7,12),(17,12)]);ln([(10,19),(14,19)])
    elif name=='library':ln([(4,3),(4,21)]);ln([(9,3),(9,21)]);ln([(15,4),(19,20)]);ln([(18,3),(22,19)])
    elif name=='queue':ln([(3,5),(16,5)]);ln([(3,11),(16,11)]);ln([(3,17),(11,17)]);ln([(18,14),(18,22)]);ln([(15,19),(18,22),(21,19)])
    elif name=='trash':ln([(4,6),(20,6)]);ln([(8,6),(8,3),(16,3),(16,6)]);ln([(6,6),(7,21),(17,21),(18,6)]);ln([(10,10),(10,17)]);ln([(14,10),(14,17)])
    elif name=='loop':ln([(5,8),(18,8),(18,4)]);ln([(18,8),(21,5)]);ln([(19,16),(6,16),(6,20)]);ln([(6,16),(3,19)])
    elif name=='fade':ln([(3,5),(7,5),(10,10),(14,14),(19,18),(22,18)]);ln([(3,21),(22,21)])
    elif name=='folder':ln([(3,7),(3,20),(21,20),(21,7),(12,7),(9,4),(3,4),(3,7)])
    elif name=='settings':
        ell(7,7,17,17)
        for n in range(8):
            a=n*math.pi/4;ln([(12+8*math.cos(a),12+8*math.sin(a)),(12+11*math.cos(a),12+11*math.sin(a))])
    elif name=='stop':box(x-size*.27,y-size*.27,size*.54,size*.54,color,r=2)
    elif name=='retry':ln([(19,8),(19,3),(14,3)]);ln([(19,8),(15,5),(8,5),(4,10),(4,16),(9,20),(16,19),(20,15)])
def cover(x,y,size,seed=0):
    palettes=[('#315248','#C9D8AB'),('#51453B','#D9B89F'),('#35474F','#A4D8D0'),('#4D414B','#D5B8D7')]
    a,b=palettes[seed%len(palettes)];box(x,y,size,size,a,r=size*.18)
    d.ellipse(tuple(round(v*S) for v in [x+size*.23,y+size*.15,x+size*.85,y+size*.77]),fill=b)
    for k in range(4):line([(x+size*.1,y+size*(.62+k*.07)),(x+size*.9,y+size*(.42+k*.07))],a,1.3)
def pill(x,y,w,label,active=False,ico=None):
    box(x,y,w,34,RAISED if active else SURF,r=10,outline=None if active else LINE)
    if ico:icon(ico,x+17,y+17,MINT if active else MUTED,16)
    text(x+(31 if ico else 12),y+10,label,12,MINT if active else FG,550)
def button(x,y,w,label,fill=MINT,ico=None):
    box(x,y,w,46,fill,r=13)
    offset=10 if ico else 0
    centered(x+w/2+offset,y+16,label,14,BG if fill in [MINT,RED] else FG,650)
    if ico:icon(ico,x+w/2-d.textlength(label,font=font(14,650))/S/2-12,y+23,BG if fill in [MINT,RED] else FG,19)
def base(title,tab=1,back=False,selection=False,foot='MOCKUP • DATA CONTOH'):
    global im,d
    im=Image.new('RGB',(W*S,H*S),BG);d=ImageDraw.Draw(im)
    text(22,12,'09:41',12,FG,650)
    for i,h in enumerate([3,5,7,9]):box(357+i*4,24-h,2,h,FG,r=1)
    box(383,13,25,12,None,r=3,outline=MUTED);box(385,15,18,8,MINT,r=1)
    if back:icon('close' if selection else 'back',29,65,FG);text(53,53,title,21,FG,700)
    else:text(20,52,title,25,FG,720)
    if not selection:
        logo=LOGO.resize((32*S,32*S),Image.Resampling.LANCZOS);im.paste(logo,(327*S,48*S),logo)
        icon('more',393,64,MUTED,23)
    line([(0,862),(432,862)],LINE,1)
    for i,(name,label) in enumerate([('library','Library'),('search','Search'),('download','Downloads')]):
        x=72+i*144
        if tab==i:box(x-30,871,60,30,RAISED,r=15)
        icon(name,x,886,MINT if i==tab else MUTED,21)
        centered(x,909,label,11,MINT if i==tab else MUTED,600 if i==tab else 450)
    box(169,932,94,3,MUTED,r=2)
    box(0,940,432,20,'#080E0B',r=0)
    centered(216,946,foot,8.5,MUTED,550)
def querybar(value='De fariz channel'):
    box(20,101,392,48,SURF,r=14,outline=LINE);icon('search',43,125,MUTED,21)
    text(63,117,value,14,FG if value else MUTED,maxw=301)
    icon('close',389,125,MUTED,17)
def search_filters(kind='Lagu'):
    pill(20,162,128,'YT Music',ico='play');icon('chevron',135,179,MUTED,14)
    pill(160,162,73,'Lagu',kind=='Lagu');pill(242,162,91,'Playlist',kind=='Playlist')

def heading(y,left,right='Relevansi'):
    text(20,y,left,13,FG,650);text(307,y,right,11,MUTED)
    if right!='Select all':icon('chevron',400,y+7,MUTED,15)
def row(y,index,selected=None,local=False,active=False):
    title,channel,duration=TRACKS[index]
    if active:box(12,y,408,76,'#182C23',r=13)
    cover(20,y+12,48,index)
    titlewidth=233 if selected is not None or local else 228
    wrapped(80,y+12,title,titlewidth,14,FG,600,2)
    label=f'MP3 · {duration}' if local else f'{channel} · {duration}'
    text(80,y+52,label,11.3,MUTED,maxw=235)
    if selected is not None:
        box(374,y+26,24,24,MINT if selected else BG,r=7,outline=None if selected else LINE)
        if selected:icon('check',386,y+38,BG,17)
    elif local:icon('more',391,y+38,MUTED,22)
    else:
        icon('pause' if active else 'play',338,y+38,MINT if active else FG,20)
        icon('download',391,y+38,MUTED,21)
    line([(80,y+77),(412,y+77)],'#223329',.6)
def mini(y=746):
    box(12,y,408,100,RAISED,r=16)
    cover(24,y+14,42,0)
    text(78,y+13,'PREVIEW',9,MINT,700);text(78,y+31,TRACKS[0][0],14,FG,600,maxw=220)
    icon('pause',338,y+34,MINT,27);icon('close',390,y+34,MUTED,21)
    box(24,y+69,384,3,'#40594C',r=2);box(24,y+69,384*42/120,3,MINT,r=2)
    box(24+384*42/120-3,y+67,6,7,MINT,r=3)
    text(24,y+81,'0:42',9,MUTED);text(372,y+81,'2:00',9,MUTED)
def export(name):
    im.save(OUT/f'{name}.png')

def search():
    base('Search',foot='MOCKUP • DATA CONTOH • PREVIEW 2 MENIT: USULAN')
    querybar();search_filters();heading(221,'6 hasil')
    for i in range(6):row(245+i*79,i,active=i==0)
    mini();export('01-search')
def playlist():
    base(f'{len(SELECTED)} selected',back=True,selection=True)
    text(320,57,'Clear',13,MINT,550)
    cover(20,102,60,1);text(94,106,'Soundcheck Kendang',18,FG,700,maxw=310)
    text(94,135,'Faris Audio · 6 lagu',12,MUTED)
    heading(191,'Pilih lagu','Select all')
    for i in range(6):row(216+i*83,i,selected=i in SELECTED)
    button(20,790,392,f'Download ({len(SELECTED)})',ico='download');export('02-playlist-select')
def downloads():
    base('Downloads',tab=2)
    text(20,105,'1 dari 3 selesai',15,FG,600);text(20,130,'Download berurutan',12,MUTED)
    icon('close',390,117,MUTED,21)
    box(20,161,392,5,LINE,r=3);box(20,161,392/3,5,MINT,r=3)
    y=201
    row(y,0,local=True);box(370,y+19,40,40,BG,r=0);icon('check',390,y+38,MINT,22)
    text(80,y+67,'Selesai',10,MINT)
    y=316
    box(12,y-5,408,166,SURF,r=16)
    cover(24,y+12,48,2);text(84,y+14,TRACKS[2][0],14,FG,600,maxw=265)
    text(84,y+40,'Downloading…',12,MINT)
    fraction=5.4/8.1
    box(24,y+87,384,4,LINE,r=2);box(24,y+87,384*fraction,4,MINT,r=2)
    text(24,y+104,'5.4 / 8.1 MB',11,MUTED);text(376,y+104,f'{fraction:.0%}',11,MINT)
    d.arc((374*S,(y+25)*S,392*S,(y+43)*S),start=15,end=285,fill=MINT,width=3)
    y=520
    cover(20,y+9,48,4);text(80,y+13,TRACKS[4][0],14,FG,600,maxw=270)
    text(80,y+40,'Menunggu',12,MUTED);icon('queue',391,y+31,MUTED,22)

    button(20,790,392,'Cancel batch',fill=SURF)
    export('03-downloads')
def library():
    base('Library',tab=0)
    querybar('Cari di library');pill(20,163,95,'Semua',True);pill(126,163,102,'Import',ico='folder');heading(220,'6 audio','Terbaru')
    for i in range(6):row(245+i*79,i,local=True)
    overlay=Image.new('RGBA',im.size,(0,0,0,0)); od=ImageDraw.Draw(overlay);od.rectangle((0,28*S,W*S,940*S),fill=(0,0,0,150));im.paste(overlay,(0,0),overlay)
    box(0,585,432,355,SURF,r=25);box(193,597,46,4,LINE,r=2)
    icon('trash',34,642,RED,25);text(59,629,'Delete audio?',21,FG,700)
    cover(24,676,48,0);text(86,682,'Cinta dan Airmata',15,FG,600);text(86,707,'MP3 · 8:56',12,MUTED)
    wrapped(24,755,'File di HP ikut dihapus. Sumber impor tetap aman.',382,14,MUTED,maxlines=2)
    button(24,845,184,'Cancel',fill=RAISED);button(220,845,188,'Delete',fill=RED,ico='trash')
    export('04-library-delete')
def player():
    base('Now playing',tab=0,back=True)
    centered(216,114,'AUDIO LOKAL',10,MINT,650)
    cover(88,160,256,0)
    text(24,455,'Cinta dan Airmata',25,FG,720,maxw=381)
    text(24,493,'MP3 · Disimpan di HP',13,MUTED)
    ratio=134/(8*60+56)
    box(24,551,384,4,LINE,r=2);box(24,551,384*ratio,4,MINT,r=2);box(24+384*ratio-5,547,10,12,MINT,r=5)
    text(24,569,'2:14',11,MUTED);text(379,569,'8:56',11,MUTED)
    icon('prev',101,647,FG,32);box(176,607,80,80,MINT,r=40);icon('pause',216,647,BG,33);icon('next',331,647,FG,32)
    box(24,726,235,49,RAISED,r=14);icon('loop',49,750,MINT,23);text(73,742,'Loop track',14,MINT,600);icon('chevron',236,750,MINT,18)
    box(272,726,136,49,SURF,r=14,outline=LINE);icon('fade',297,750,MINT,23);text(319,742,'Fade 3s',14,FG,550)
    icon('queue',37,821,MUTED,22);text(58,813,'Queue',14,FG,550);text(354,815,'6 audio',11,MUTED)
    export('05-player')
def loading(phase=0):
    base('Search',foot='MOCKUP • DATA CONTOH • ANIMASI LOADING')
    querybar();search_filters();text(20,222,'Mencari…',13,MUTED);text(364,222,'Cancel',12,MINT,550)
    for i in range(6):
        y=251+i*79;box(20,y+9,48,48,SURF,r=10)
        box(80,y+13,210-(i%3)*30,12,SURF,r=5);box(80,y+35,150,9,SURF,r=4)
        xx=20+((phase*43+i*22)%290)
        for yy,ww in [(y+13,210-(i%3)*30),(y+35,150)]:
            if 80<xx<80+ww:box(xx,yy,min(25,80+ww-xx),12 if yy==y+13 else 9,'#2C4438',r=4)
    angle=phase*math.pi/5
    cx,cy=341,229
    d.arc(tuple(round(v*S) for v in [cx-7,cy-7,cx+7,cy+7]),start=phase*36,end=phase*36+245,fill=MINT,width=3)
    return im.copy()
search();playlist();downloads();library();player()
frames=[loading(i) for i in range(10)]
frames[0].save(OUT/'06-search-loading.png')
frames[0].save(OUT/'06-search-loading.gif',save_all=True,append_images=frames[1:],duration=100,loop=0,optimize=False)
NAMES=['01-search','02-playlist-select','03-downloads','04-library-delete','05-player','06-search-loading']
manifest={'screens':NAMES,'sample_data':True,'dimensions':[864,1920],'preview_duration_proposal_seconds':120,'application_changed':False,'notes':'Concepts from supplied phone screenshots. All tracks, covers, counts, times and progress are sample design data. GIF shows proposed indeterminate search animation, not network measurement.'}
(ROOT/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
# Optional overview, not a substitute for inspecting individual screens.
thumbs=[]
for name in NAMES:
    img=Image.open(OUT/f'{name}.png');img.thumbnail((324,720));thumbs.append(img)
sheet=Image.new('RGB',(324*3,720*2),'#080E0B')
for i,img in enumerate(thumbs):sheet.paste(img,((i%3)*324,(i//3)*720))
sheet.save(OUT/'overview.jpg',quality=94)
print(json.dumps(manifest,indent=2))
