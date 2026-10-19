import sys
sys.path.insert(0, sys.argv[1])
import py7zr
with py7zr.SevenZipFile(sys.argv[2], "r") as z:
    bad = z.testzip()
    print("crc failures:", bad)
with py7zr.SevenZipFile(sys.argv[2], "r") as z:
    z.extractall(sys.argv[3])
