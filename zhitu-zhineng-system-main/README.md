# 鑱岄€旀櫤閰?鈥?鍩轰簬鍙俊鏅鸿兘浣撶殑宀椾綅鑳藉姏鍥捐氨鏋勫缓涓庝汉宀楀尮閰嶈瘖鏂郴缁?

鈥滆亴閫旀櫤閰嶁€濇槸闈㈠悜鎸戞垬鏉禌棰?XH-202621 鐨勫畬鏁村墠鍚庣宸ョ▼锛岃鐩栧婧愭暟鎹不鐞嗐€佹柊宀椾綅鍙戠幇涓庡畾涔夈€佹棦鏈夊矖浣嶈兘鍔涘姩鎬佹紨鍖栥€佹妧鑳界偣绾у叏鏅浘璋便€佺畝鍘嗚В鏋愩€佷汉宀楀尮閰嶃€佸樊璺濊瘖鏂€佸涔犺矾寰勩€佸彲淇″鏍搞€佽瘉鎹函婧愬強鍏櫤鑳戒綋鍗忓悓闂瓟銆?

## 鎶€鏈爤

- 鍚庣锛欽ava 17銆丼pring Boot 3.5.16銆丮aven銆丼pring JDBC銆丮ySQL 8銆丠2銆丷edis銆丄pache Tika銆丣soup銆丱penAI-Compatible API
- 鍓嶇锛歏ue 3銆乀ypeScript銆乂ite銆丳inia銆乂ue Router銆丒lement Plus銆丒Charts銆丟SAP銆丩ucide
- 閮ㄧ讲锛欴ocker Compose銆丯ginx
- 娴嬭瘯锛欽Unit 5銆丣aCoCo銆丳ython 鎸囨爣鑴氭湰銆?20 鏉″矖浣?JD 涓庨噾鏍囧噯鏍锋湰

## 鍏櫤鑳戒綋

1. 鏁版嵁娌荤悊鏅鸿兘浣擄細CSV銆丳DF銆乄ord銆丠TML銆佹枃鏈拰鍏紑缃戦〉閲囬泦锛屾竻娲椼€侀噸澶嶆娴嬨€佹椂婊炲拰璐ㄩ噺璇勫垎銆?
2. 宀椾綅娲炲療鏅鸿兘浣擄細JD 瑙ｆ瀽銆佹柊宀椾綅鍙戠幇銆佹棦鏈夊矖浣嶆妧鑳芥柊澧?寮卞寲/淇敼鍒嗘瀽銆?
3. 鑳藉姏鍥捐氨涓庢紨鍖栨櫤鑳戒綋锛氬矖浣嶁€旀妧鑳解€旀妧鏈爤鈥旂瓑绾у叧绯诲強璇佹嵁鍙鍖栵紝浠ュ強宀椾綅鑳藉姏鍔ㄦ€佹紨鍖栥€?
4. 鐢诲儚鍖归厤鏅鸿兘浣擄細绠€鍘嗚В鏋愩€佷簲缁村尮閰嶅拰鎶€鑳藉樊璺濊瘖鏂€?
5. 瀛︿範瑙勫垝鏅鸿兘浣擄細渚濇嵁缂哄け鎶€鑳姐€佸墠缃叧绯汇€佸懆鏁板拰宸ユ椂鐢熸垚瀛︿範璺緞銆?
6. 鍙俊瀹℃牳鏅鸿兘浣擄細澶氭簮浜ゅ弶楠岃瘉銆佺疆淇″害銆佸够瑙夐闄┿€佷汉宸ヤ慨鏀?閫氳繃/椹冲洖鍜屽璁¤褰曘€?

## Docker 涓€閿惎鍔?

```bash
cp .env.example .env
docker compose up --build
```

鍓嶇璁块棶 `http://localhost:5173`锛屽悗绔仴搴锋鏌ヤ负 `http://localhost:8080/actuator/health`銆?

## IDEA 鏈湴鍚姩

1. IDEA 鎵撳紑 `backend/pom.xml`锛岄」鐩?SDK 閫夋嫨 JDK 17骞堕噸鏂板姞杞?Maven銆?
2. 杩愯 `com.zhitu.ZhituApplication`锛涢粯璁や娇鐢?H2 鍐呭瓨鏁版嵁搴撱€?
3. 鍦?`frontend` 鐩綍鎵ц `npm install`銆乣npm run dev`銆?
4. 娴忚鍣ㄨ闂?`http://localhost:5173`銆?

棣栨鍚庣鍚姩浼氬鍏?120 鏉?JD锛屼繚鐣欏苟鏍囨敞閲嶅璇佹嵁锛岀劧鍚庣敓鎴愬浘璋便€佸€欓€夊矖浣嶃€佹紨鍖栦簨浠躲€佹紨绀哄尮閰嶇粨鏋滀笌瀛︿範璺緞銆傛洿璇︾粏姝ラ瑙?`docs/IDEA_涓枃鐗堥儴缃叉寚鍗?md`銆?

## 娴嬭瘯涓庨獙鏀?

```bash
python scripts/validate_project.py
cd backend && mvn clean verify
cd ../frontend && npm install && npm run type-check && npm run build
```

褰撳墠绂荤嚎鎸囨爣锛欽D 鎶€鑳芥娊鍙?F1 = 1.0000锛岀畝鍘嗘妧鑳芥娊鍙?F1 = 0.9744銆傚尮閰嶅噯纭巼蹇呴』鍩轰簬涓撳鏍囨敞鏍锋湰璁＄畻锛屽伐绋嬫病鏈変吉閫犺鎸囨爣銆傚畬鏁磋鏄庤 `docs/VALIDATION_REPORT.md`銆?

## 鍏抽敭鏂囨。

- `docs/ARCHITECTURE.md`锛氱郴缁熸灦鏋勪笌鍙俊鏈哄埗
- `docs/FEATURE_MATRIX.md`锛氳禌棰樿姹傗€斿姛鑳芥槧灏?
- `docs/API.md`锛氬悗绔帴鍙ｆ竻鍗?
- `docs/TEST_PLAN.md`锛氭祴璇曟柟妗堜笌鎸囨爣鍙ｅ緞
- `docs/DEMO_SCRIPT.md`锛?0 鍒嗛挓婕旂ず鑴氭湰
- `docs/IDEA_涓枃鐗堥儴缃叉寚鍗?md`锛歐indows/IDEA 鎿嶄綔姝ラ

## 澶фā鍨嬮厤缃?

闂瓟妯″潡閲囩敤鈥滄不鐞嗘暟鎹绱?+ 澶фā鍨嬬敓鎴愨€濈殑璇佹嵁闂瓟閾捐矾锛氬厛浠?`zhitu_governed_job`銆乣zhitu_governed_job_skill` 鍜屼笟鍔″垎鏋愯〃涓绱㈣瘉鎹紝鍐嶅皢璇佹嵁浜ょ粰 OpenAI-Compatible 妯″瀷鐢熸垚鍥炵瓟銆傚洖绛斾腑鐨?`[璇佹嵁N]` 涓庡墠绔€滄煡鐪嬫暟鎹瘉鎹€濆垪琛ㄤ竴涓€瀵瑰簲銆?

鍦?`.env` 涓嚦灏戝～鍐欙細

```dotenv
AI_ENABLED=true
AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_API_KEY=your_ai_api_key_here
AI_MODEL=qwen-plus
```

瀵嗛挜鍙橀噺鍏煎 `AI_API_KEY`銆乣DASHSCOPE_API_KEY` 鍜?`ALI_API_KEY`锛屾寜姝ゅ墠椤哄簭璇诲彇锛涘彧闇€閰嶇疆鍏朵腑涓€涓€傛娴嬪埌瀵嗛挜鍚庝細榛樿鍚敤妯″瀷锛屼篃鍙敤 `AI_ENABLED=false` 鏄惧紡鍏抽棴銆傛湭閰嶇疆瀵嗛挜鎴栨ā鍨嬭皟鐢ㄥけ璐ユ椂锛屾帴鍙ｄ細鏄庣‘闄嶇骇涓衡€滄绱㈡ā寮忊€濓紝涓嶄細鍐嶇敤鍐欐鐨勫厹搴曞洖绛斾吉瑁呮垚妯″瀷缁撴灉銆俙sessionId` 鐢ㄤ簬淇濆瓨鏈€杩?6 鏉″璇濓紝鏀寔鍩轰簬璇佹嵁鐨勮繛缁拷闂€?
鍥剧墖绠€鍘嗚В鏋愰噰鐢ㄢ€滃妯℃€佽鍥句紭鍏?+ OCR 鍏滃簳 + 鍘熸枃璇佹嵁闂ㄧ鈥濄€侱eepSeek 鏂囨湰妯″瀷鐢ㄤ簬缁撴瀯鍖栨牎鍑嗭紱濡傛灉瑕佽绯荤粺鐩存帴鐞嗚В鍥剧墖鐗堥潰锛岄渶瑕侀澶栭厤缃竴涓?OpenAI-Compatible 瑙嗚妯″瀷绔偣锛?
```dotenv
AI_RESUME_VISION_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_RESUME_VISION_API_KEY=your_qwen_vl_api_key_here
AI_RESUME_VISION_MODEL=qwen-vl-plus
```

鑻ヨ瑙夋ā鍨嬫湭閰嶇疆锛岀郴缁熶細鍦ㄩ〉闈腑鏍囪鈥滅瓑寰呭妯℃€佽瑙夋ā鍨嬧€濓紝骞惰嚜鍔ㄨ蛋 OCR/鏍锋湰鏍″噯鍏滃簳锛屼笉鍐嶆妸鏂囨湰妯″瀷璇綋鎴愬浘鐗囨ā鍨嬨€?
## 鐧句竾鍘嗗彶鏁版嵁锛氬勾搴︽柊宀椾綅棰勬祴涓庡洖娴嬶紙V3锛?
绯荤粺鍙洿鎺ヨ繛鎺ュ凡鏈?`career_data_governance.dataset_job_raw` 鐧句竾绾?MySQL 鍘熷宀椾綅搴擄紝涓嶈縼绉汇€佷笉鍒犻櫎鍘熷鏁版嵁銆?

- 鍥哄畾鎶藉彇 2026 骞?1000 鏉′綔涓烘渶缁?holdout 娴嬭瘯闆嗭紱鍚屼竴 seed 鍙鐜般€?
- 鍏朵綑鏁版嵁鍧囦繚鐣欏湪璁粌姹犮€?
- 鎵ц 2020鈫?021銆?021鈫?022 鈥︹€?鐨勪弗鏍煎勾搴︽粴鍔ㄥ洖娴嬨€?
- 2025鈫?026 鍙敤鍥哄畾 1000 鏉℃祴璇曢泦璁＄畻鏈€缁堟寚鏍囷紝闃叉娴嬭瘯姹℃煋銆?
- 鍓嶇鏂板鈥滈獙璇佲€濋〉闈紝灞曠ず骞村害鏍锋湰銆丳recision@K銆丷ecall銆丗1銆丆alibration銆乀rust Score 鍜屾瘡涓€欓€夌殑璇佹嵁銆?
- 瀹為獙缁撴灉鍐欏叆鐙珛 `zhitu_*` 琛紝涓嶈鐩栧師涓氬姟琛ㄣ€?

璇︾粏閰嶇疆涓庢寚鏍囧彛寰勮 `docs/骞村害鏂板矖浣嶉娴嬩笌楠岃瘉鎸囧崡.md`銆?

## V4锛氱櫨涓囧巻鍙?JD 杩炵画娌荤悊

V4 涓嶅啀鍋囪 `dataset_job_raw` 涓€瀹氬叿鏈?`title/company/published_at` 鑻辨枃瀛楁銆傜郴缁熶細浠?`information_schema` 鑷姩璇嗗埆锛?

- `鎷涜仒宀椾綅 / 宀椾綅鍚嶇О / title / job_title`
- `浼佷笟鍚嶇О / 鍏徃鍚嶇О / company`
- `鑱屼綅鎻忚堪 / 宀椾綅鎻忚堪 / description`
- `鎷涜仒鍙戝竷鏃ユ湡 / 鍙戝竷鏃ユ湡 / published_at`
- `鎷涜仒鍙戝竷骞翠唤 / 鍙戝竷骞翠唤 / published_year`

杩愯椤哄簭锛?

1. IDEA 鍚姩鍚庣銆?
2. 鎵撳紑鍓嶇鈥滆В鏋愨€濋〉闈€?
3. 鐐瑰嚮鈥滃紑濮嬭繛缁不鐞嗏€濄€傚悗绔細鍏堝浐瀹?2026 骞?1000 鏉?holdout銆?
4. 鍏朵綑 `dataset_job_raw` 璁板綍鎸?ID 姣忔壒 1000 鏉¤繛缁不鐞嗐€?
5. 鍙互鈥滃畨鍏ㄦ殏鍋溾€濓紝涓嬫鈥滀粠鏂偣缁х画鈥濄€?
6. 娌荤悊瀹屾垚鍚庡啀杩涘叆鈥滈獙璇佲€濋〉闈㈣繍琛?2020鈫?026 骞村害婊氬姩鍥炴祴銆?

鍏ㄩ噺闃舵閲囩敤纭畾鎬ц鍒欍€佹妧鑳借瘝鍏搞€佽川閲忚瘎鍒嗗拰妯℃澘鎸囩汗锛屼笉閫愭潯璋冪敤澶фā鍨嬶紱杩欐牱鐧句竾绾т换鍔″彲澶嶇幇銆佹垚鏈彲鎺с€備綆缃俊缁撴灉浠嶅彲鍦ㄥ彲淇″鏍搁樁娈佃繘涓€姝ヤ娇鐢ㄥぇ妯″瀷澶嶆牳銆?

鍘熷琛ㄤ笉浼氳 UPDATE/DELETE銆傛淳鐢熺粨鏋滃啓鍏ワ細

- `zhitu_governed_job`
- `zhitu_governed_job_skill`
- `zhitu_governance_issue`
- `zhitu_governance_run`
- `zhitu_duplicate_cluster`
- `zhitu_temporal_holdout`

## V5锛氱櫨涓囨不鐞嗘暟鎹叏绯荤粺鑱斿姩

V5 灏?`zhitu_governed_job` 涓?`zhitu_governed_job_skill` 浣滀负宀椾綅甯傚満鍒嗘瀽涓绘暟鎹簮锛屽畬鎴愭€昏銆佹暟鎹€佹帰鏂般€侀獙璇併€佹紨鍖栧拰鍥捐氨鐨勫叏閾捐矾鑱斿姩銆?

- JD 瑙ｆ瀽椤碘€滄渶杩戞不鐞嗙粨鏋滄娊鏍封€濆拰鈥滅郴缁熷唴鍗虫椂瑙ｆ瀽璁板綍鈥濆鍔犵嫭绔嬫粴鍔ㄥ鍣ㄤ笌鍚搁《琛ㄥご銆?
- 鎬昏鈥滃畬鏁翠笟鍔￠棴鐜€濈Щ鍔ㄥ埌鎶€鏈爤鍒嗗竷涔嬪墠锛?1~06 鍙洿鎺ヨ烦杞搴旈〉闈€?
- 鎺㈡柊缁撴灉瀹屾暣杈撳嚭锛氬矖浣嶅悕绉般€佹牳蹇冭亴璐ｃ€佸繀澶囨妧鑳姐€佸姞鍒嗘妧鑳姐€佸吀鍨嬭涓氬簲鐢ㄥ満鏅€?
- 鏂板矖浣嶃€佽兘鍔涙紨鍖栫粨鏋滅敱鐧句竾娌荤悊鏁版嵁閲嶆柊璁＄畻锛屽苟缁х画闀滃儚鍒?H2 瀹℃牳琛紝淇濇寔浜哄伐瀹℃牳鍏煎銆?
- 骞村害楠岃瘉灞曠ず鍘熷璁粌姹犮€佸凡娌荤悊鍜屾湁鏁堣缁冩暟閲忥紝璁粌浠呬娇鐢ㄦ不鐞嗗悗 `valid_for_analysis=1` 鏁版嵁銆?
- 鍥捐氨鐩存帴鏍规嵁娌荤悊鍚庡矖浣嶁€旀妧鑳借瘉鎹姩鎬佹瀯寤猴紝閲嶅妯℃澘鎸?`duplicate_weight` 闄嶆潈銆?

璇︾粏璇存槑瑙?`docs/V5鐧句竾娌荤悊鏁版嵁鍏ㄧ郴缁熻仈鍔ㄨ鏄?md`锛涢€愭枃浠跺畬鏁存簮鐮佽 `docs/V5淇敼鏂囦欢瀹屾暣浠ｇ爜.md`銆?

