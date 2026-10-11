"""Temporary local E2E upstream replay; application views/services are unchanged."""
import json
import os
from pathlib import Path
import sys
from urllib.parse import parse_qs, urlparse

root=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'backend'))
assert os.environ['DATABASE_URL'].startswith('sqlite:///')
os.environ['DJANGO_SETTINGS_MODULE']='config.settings.dev'
import django
django.setup()
from django.conf import settings
from django.core.management import call_command
from apps.routing import clients

for name in ('KAKAO_REST_API_KEY','KAKAO_MOBILITY_KEY','OPENAI_API_KEY','SEOUL_SUBWAY_API_KEY','SEOUL_BUS_API_KEY_ENCODING','SEOUL_BUS_API_KEY_DECODING'):
    setattr(settings,name,'')
fixture=json.loads((Path(__file__).parent/'replay/route.json').read_text())
home=fixture['home']; destination=fixture['destination']; transit_dest=fixture['transit_destination']
map_bytes=(Path(__file__).parent/'replay/map.png').read_bytes()
meta=lambda count:{'is_end':True,'total_count':count,'pageable_count':count}

def bus(name,start,end,seconds,alternatives=()):
    return {'properties':{'type':'BUS','time':seconds,'vehicles':[{'name':name,'type':'지선'},*alternatives],
            'stops':[{'name':'승차 정류장'},{'name':'하차 정류장'}]},
            'path':{'points':[[start[1],start[0]],[end[1],end[0]]]}}

def replay_get(url):
    base=url.split('?')[0]; params=parse_qs(urlparse(url).query)
    query=params.get('query',[''])[0]
    if base==clients.ADDRESS_SEARCH_URL:
        documents=[]
        if query==home['name']:
            documents=[{'address_type':'ROAD_ADDR','address_name':home['address'],
                'road_address':{'address_name':home['address'],'x':str(home['lng']),'y':str(home['lat'])},
                'address':{'address_name':home['address']},'x':str(home['lng']),'y':str(home['lat'])}]
        return {'meta':meta(len(documents)),'documents':documents},False
    if base==clients.LOCAL_SEARCH_URL:
        selected=next((p for p in (destination,transit_dest) if query==p['name']),None)
        documents=[] if selected is None else [{'id':selected['kakao_place_id'],'place_name':selected['name'],
            'address_name':selected['address'],'road_address_name':selected['address'],
            'x':str(selected['lng']),'y':str(selected['lat']),'category_name':'음식점 > 카페',
            'category_group_name':'카페','category_group_code':'CE7','distance':''}]
        return {'meta':meta(len(documents)),'documents':documents},False
    if base==clients.COORD_TO_ADDRESS_URL:
        return {'documents':[{'road_address':{'address_name':home['address']},'address':{'address_name':home['address']}}]},False
    if base==clients.CAR_URL:
        return {'routes':[{'summary':{'duration':480,'distance':5000,'fare':{'taxi':7000}}}]},False
    end_lat=float(params.get('end_y',[destination['lat']])[0])
    end_lng=float(params.get('end_x',[destination['lng']])[0])
    display=abs(end_lat-transit_dest['lat'])<0.0001 and abs(end_lng-transit_dest['lng'])<0.0001
    start=(home['lat'],home['lng']); end=(end_lat,end_lng)
    if base==clients.TRANSIT_URL:
        first=bus('5511',start,end,1080,[{'name':'5513','type':'지선'},{'name':'643','type':'간선'}])
        middle=((start[0]+end[0])/2,(start[1]+end[1])/2)
        second=[bus('501',start,middle,700),bus('502',middle,end,680)]
        return {'status':'OK','routes':[
            {'properties':{'totalTime':1200,'totalDistance':5000,'transfers':0,'fare':{'value':1500}},'steps':[first]},
            {'properties':{'totalTime':1500,'totalDistance':6000,'transfers':1,'fare':{'value':1800}},'steps':second}]},False
    if base in (clients.WALK_URL,clients.BICYCLE_URL):
        walk=fixture['walk']
        seconds=2400 if display else walk['minutes']*60
        if base==clients.BICYCLE_URL: seconds=900
        distance=5000 if display else walk['distance_m']
        points=[[start[1],start[0]],[end[1],end[0]]] if display else [[lng,lat] for lat,lng in walk['path']]
        return {'status':'OK','route':{'properties':{'totalTime':seconds,'totalDistance':distance},
            'legs':[{'steps':[{'properties':{'type':'WALKING','time':seconds},'path':{'points':points}}]}]}},False
    raise AssertionError('Unexpected replay upstream '+base)

clients._get=replay_get
clients.static_map=lambda *args,**kwargs:(map_bytes,'image/png',False)
print('Local backend; upstream routing replay from '+fixture['provenance'],flush=True)
call_command('runserver','127.0.0.1:'+sys.argv[1],use_reloader=False,use_threading=False)
