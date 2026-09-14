"""Rebuild installer artwork with Python and Pillow; no runtime dependency."""
from pathlib import Path
from math import cos, sin, pi
from PIL import Image, ImageDraw

OUT = Path(__file__).parent / "setup"
OUT.mkdir(exist_ok=True)
S = 3

# The same compact mark is used for the native title-bar/taskbar/Explorer
# icon. Keep the dark tile behind the white mark so it remains legible on both
# light and dark Windows shells.
logo = Image.open(Path(__file__).parent / "synesis-logo.png").convert("RGBA")
icon = Image.new("RGBA", (256, 256), (18, 28, 42, 255))
icon_draw = ImageDraw.Draw(icon)
icon_draw.rounded_rectangle((4, 4, 252, 252), radius=38, outline=(76, 104, 139, 255), width=5)
logo.thumbnail((174, 174), Image.Resampling.LANCZOS)
icon.alpha_composite(logo, ((256-logo.width)//2, (256-logo.height)//2))
icon.save(Path(__file__).parent / "synesis-icon.png")


def panel(name, width, height, top, bottom, border, radius):
    image = Image.new("RGBA", (width*S, height*S))
    draw = ImageDraw.Draw(image)
    for y in range(height*S):
        t = y / (height*S-1)
        color = tuple(round(a*(1-t)+b*t) for a, b in zip(top, bottom))
        draw.line((0, y, width*S, y), fill=color)
    mask = Image.new("L", image.size)
    ImageDraw.Draw(mask).rounded_rectangle((1, 1, width*S-2, height*S-2), radius*S, fill=255)
    image.putalpha(mask)
    draw.rounded_rectangle((2, 2, width*S-3, height*S-3), radius*S, outline=border, width=2*S)
    image.resize((width, height), Image.Resampling.LANCZOS).save(OUT / f"{name}.png")


panel("card", 1280, 96, (25,29,35), (22,26,31), (55,62,72), 15)
panel("card-selected", 1280, 96, (27,42,60), (24,38,55), (60,166,249), 15)
panel("card-disabled", 1280, 96, (22,25,30), (20,23,28), (40,46,55), 15)
panel("primary", 175, 64, (27,124,237), (10,88,188), (33,124,218), 10)
panel("secondary", 165, 64, (35,40,48), (29,34,41), (60,68,81), 10)
panel("brand", 190, 190, (36,44,54), (18,25,33), (58,68,81), 19)

# A softly fading, rounded diagonal ribbon, kept behind the brand region.
image = Image.new("RGBA", (360*S, 310*S))
draw = ImageDraw.Draw(image)
draw.line((170*S,-50*S, 10*S,150*S), fill=(23,28,35,255), width=82*S)
draw.ellipse((-31*S,109*S,51*S,191*S), fill=(23,28,35,255))
draw.line((400*S,80*S,180*S,300*S), fill=(20,25,32,255), width=64*S)
for y in range(image.height):
    alpha = max(0, min(255, round(255*(1-y/image.height))))
    for x in range(image.width):
        r,g,b,a = image.getpixel((x,y))
        if a:
            image.putpixel((x,y),(r,g,b,alpha))
image.resize((360,310), Image.Resampling.LANCZOS).save(OUT / "ribbon.png")

for name in ("install", "link", "data"):
    image = Image.new("RGBA", (32*S,32*S))
    draw = ImageDraw.Draw(image)
    ink = (226,232,241)
    def line(points, width=2):
        draw.line([(round(x*S),round(y*S)) for x,y in points], fill=ink, width=width*S, joint="curve")
    if name == "install":
        points=[]
        for i in range(64):
            angle=2*pi*i/64
            radius=12 if i%8 in (1,2,3,4) else 9
            points.append(((16+cos(angle)*radius)*S,(16+sin(angle)*radius)*S))
        draw.polygon(points, fill=ink)
        draw.ellipse((12*S,12*S,20*S,20*S), fill=(15,18,24))
    elif name == "link":
        # Two open diagonal links with a clear central connecting stroke.
        line([(14,11),(18,7),(21,6),(24,7),(26,10),(26,13),(21,18),(18,18)])
        line([(18,21),(14,25),(11,26),(8,25),(6,22),(6,19),(11,14),(14,14)])
        line([(12,20),(20,12)])
    else:
        draw.ellipse((5*S,4*S,27*S,13*S), outline=ink, width=2*S)
        line([(5,9),(5,24)])
        line([(27,9),(27,24)])
        for y in (12,19):
            draw.arc((5*S,y*S,27*S,(y+10)*S),0,180,fill=ink,width=2*S)
    image.resize((32,32),Image.Resampling.LANCZOS).save(OUT / f"{name}.png")
