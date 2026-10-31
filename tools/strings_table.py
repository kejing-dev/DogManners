#!/usr/bin/env python3
"""Single source of the app copy (zh / en). Copy comes from the design package (content.*, states[], panels[]) and the
sample page (COACH, T.zh / T.en); lines the design did not write follow content.tone. Writes res/values/strings.xml
(English, default) and res/values-zh/strings.xml.   python3 tools/strings_table.py"""
import os
from xml.sax.saxutils import escape

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
S = [
    # key, zh, en
    ("app_name", "别盯着狗", "Dog Manners"),
    ("lang_code", "zh", "en"),
    ("starting", "正在摆公园…", "Setting up the park…"),
    # S3 disclaimer
    ("disclaimer_title", "开始前说一句", "Quick note first"),
    ("disclaimer", "开始前说一句：每只狗脾气都不一样，这里学的是通用的礼貌，不是防咬保险。遇到真狗，主人最懂它，听主人的。",
     "Every dog is different. This is general dog manners, not bite insurance. With a real dog, the owner knows it best. Listen to them."),
    ("disclaimer_eye", "我们想用眼动看看你有没有盯着它。不开也能玩，会改用你的脸朝哪边来判断。",
     "We'd like to use eye tracking to tell if you're staring at it. You can skip it. We'll go by where your face points instead."),
    ("start", "开始", "Let's go"),
    # S2 home
    ("home_one", "有只狗朝你走来。它不听你说话，只看你怎么站。", "A dog's heading your way. It isn't listening to you. It's watching how you stand."),
    ("guide", "狗语小词典", "Dog-speak cheat sheet"),
    ("free", "随便逛逛", "Just hang out"),
    # footer
    ("lang_zh", "中", "中"),
    ("lang_en", "EN", "EN"),
    ("sound_on", "声音 开", "Sound on"),
    ("sound_off", "声音 关", "Sound off"),
    ("bring", "回到面前", "Bring to front"),
    ("settings", "设置", "Settings"),
    # S4
    ("eye_off", "眼动没开，现在用头的朝向判断", "Eye tracking's off, so we're going by where your face points."),
    ("eye_turn_on", "去开启", "Turn on"),
    ("eye_denied", "到 设置 > 应用 > 别盯着狗 > 权限 打开眼动", "Turn it on in Settings > Apps > Dog Manners > Permissions"),
    ("eye_on_state", "眼动：已开启", "Eye tracking: on"),
    ("eye_off_state", "眼动：没开", "Eye tracking: off"),
    ("eye_unsupported", "这台设备拿不到眼动，用头的朝向判断", "No eye tracking on this device. Going by your face."),
    # lessons
    ("l1_name", "路上来了只狗", "A dog on the path"),
    ("l1_tag", "别盯它，侧过身，让它先闻闻你", "Don't stare, turn sideways, let it sniff you"),
    ("l2_name", "能摸吗？先问问", "Can I pet it?"),
    ("l2_tag", "小狗很可爱，但它有主人", "Cute dog. It still has an owner"),
    ("l3_name", "干饭中，勿扰", "Do not disturb: dinner"),
    ("l3_tag", "它在吃饭，你在等", "It eats, you wait"),
    ("l4_name", "睡得正香", "Let sleeping dogs lie"),
    ("l4_tag", "叫醒一只狗，后果自负", "Wake a dog at your own risk"),
    ("l5_name", "狗妈妈和小崽", "Mom and pup"),
    ("l5_tag", "小狗再萌，也别凑近", "Cute pup. Still, keep back"),
    ("l6_name", "栅栏后面那位", "The dog behind the fence"),
    ("l6_tag", "栅栏后面是它的地盘", "Behind the fence is its turf"),
    # intro bullets
    ("l1_i1", "别一直盯着它的眼睛，用余光瞄就行。", "Don't lock eyes. Watch it out of the corner of your eye."),
    ("l1_i2", "脸转向左边，侧个身。", "Turn your face to the left, a bit sideways."),
    ("l1_i3", "它过来闻你，站着别动，手垂着。", "When it sniffs you, hold still, hands down."),
    ("l2_i1", "狗是别人的，先问主人。", "It's someone else's dog. Ask the owner first."),
    ("l2_i2", "主人说可以，也让它先闻闻你。", "Even if they say yes, let it sniff you first."),
    ("l2_i3", "别在它旁边跑。", "Don't run past it."),
    ("l3_i1", "狗吃饭的时候会护着碗。", "Dogs can guard their food."),
    ("l3_i2", "别过去，也别盯着它。", "Don't walk over, don't stare."),
    ("l3_i3", "侧过身，等它吃完。", "Turn sideways and wait till it's done."),
    ("l4_i1", "睡着的狗被吵醒会吓一跳。", "A sleeping dog can wake with a fright."),
    ("l4_i2", "别叫它，也别拍它。", "Don't call it, don't tap it."),
    ("l4_i3", "等它自己醒。", "Let it wake up on its own."),
    ("l5_i1", "狗妈妈特别护崽。", "Mom dogs guard their pups."),
    ("l5_i2", "这次它更敏感，盯一下就紧张。", "She's touchier. A short stare is enough to worry her."),
    ("l5_i3", "远远看，别凑近。", "Watch from a distance."),
    ("l6_i1", "栅栏后面是它的地盘。", "The yard is its turf."),
    ("l6_i2", "别隔着栅栏伸手。", "Don't reach through or over the fence."),
    ("l6_i3", "想认识它，等主人来。", "Want to meet it? Wait for the owner."),
    # questions + choices
    ("l2_q", "主人牵着一只小狗过来了。你特别想摸摸它，怎么办？", "Someone's walking a little dog your way. You really want to pet it. What now?"),
    ("l2_c1", "冲上去摸摸", "Rush over and pet it"),
    ("l2_c2", "先问主人：它能摸吗？", "Ask first: can I pet it?"),
    ("l2_c3", "撒腿就跑", "Run for it"),
    ("l3_q", "它正埋头干饭。你挺想跟它打个招呼，怎么办？", "It's busy eating. You'd love to say hi. What now?"),
    ("l3_c1", "过去摸两下", "Go give it a pat"),
    ("l3_c2", "等它吃完再说", "Wait till it's done"),
    ("l4_q", "它在垫子上睡得正香。你想跟它玩，怎么办？", "It's fast asleep on its mat. You want to play. What now?"),
    ("l4_c1", "叫醒它一起玩", "Wake it up to play"),
    ("l4_c2", "轻轻拍一下", "Give it a gentle pat"),
    ("l4_c3", "让它接着睡", "Let it sleep"),
    ("l5_q", "狗妈妈带着小崽在那边。小崽太萌了，怎么办？", "A mom and her pup are over there. The pup is adorable. What now?"),
    ("l5_c1", "凑近看看小狗", "Go see the pup up close"),
    ("l5_c2", "远远看着就好", "Just watch from here"),
    ("l6_q", "栅栏后面有只大狗看着你。你想隔着栅栏摸摸它，怎么办？", "A big dog is watching you from behind a fence. You want to reach over and pet it. What now?"),
    ("l6_c1", "手伸过去摸摸", "Reach over and pet it"),
    ("l6_c2", "不伸手，等主人来", "Don't reach, wait for the owner"),
    ("choice_hint", "慢慢想，选一个就行。", "Take your time. Just pick one."),
    # coach lines (sample COACH + tone)
    ("c_approach", "有只柴犬过来了。别一直看它，脸转向左边，用余光瞄着就行。", "Here comes a Shiba. Don't keep looking at it. Turn your face left and watch out of the corner of your eye."),
    ("c_approach_dog", "有只狗过来了。别一直看它，脸转向左边，用余光瞄着就行。", "Here comes a dog. Don't keep looking at it. Turn your face left and watch out of the corner of your eye."),
    ("c_wary", "它站住了，耳朵往后贴。侧个身，看看旁边的树，别往前凑。", "It stopped and flattened its ears. Turn sideways, check out the trees, don't lean in."),
    ("c_relaxed", "它松下来了，在往你这边走。站着别动就好。", "It's loosening up and coming your way. Just stand still."),
    ("c_sniff", "它在闻你，可能在查你早饭吃了啥。手垂着，别动。", "It's sniffing you. Probably checking what you had for breakfast. Hands down, hold still."),
    ("c_play", "它屁股撅起来了，这是在叫你玩！不过主人不在，先忍住别伸手，让它自己走。", "Butt up, chest down: it wants to play! No owner around though, so keep your hands to yourself and let it wander off."),
    ("c_too_close", "凑太近啦，它往后躲了一下", "Too close! It backed off a bit."),
    ("c_left", "它溜了。没事，再来一次：别盯，侧个身，站稳。", "It took off. No big deal, go again: don't stare, turn a bit sideways, stand still."),
    ("c_left_well", "漂亮！它闻完你，心满意足地走了。跟陌生狗打交道就这么简单：不盯、不凑、不伸手。", "Nice! It sniffed you and trotted off happy. That's all it takes with a strange dog: no staring, no leaning, no reaching."),
    ("c_owner_ok", "主人：「它有点怕生，让它先闻闻你吧。」", "Owner: \"It's a bit shy. Let it sniff you first.\""),
    ("c_owner_held", "主人牵着它站在那边。", "The owner's holding it on the leash over there."),
    ("c_l2_rush", "它一下僵住了，耳朵往后贴，主人赶紧把它拉回去。", "It froze and pinned its ears back. The owner pulled it away."),
    ("c_l2_run", "你一跑，它兴奋地追过来，牵绳一绷才停住。", "You ran, so it chased you till the leash ran out."),
    ("c_eating", "它在埋头干饭。", "It's busy eating."),
    ("c_l3_wait", "侧过身等一会儿，别盯着它的碗。", "Turn sideways and wait. Don't stare at its bowl."),
    ("c_l3_wary", "它停嘴了，在盯你。侧个身，看看别处。", "It stopped eating and it's watching you. Turn a bit, look somewhere else."),
    ("c_l3_pat", "它一下停嘴，定住了，护着碗盯着你。", "It stopped mid-bite, froze, and guarded its bowl."),
    ("c_sleeping", "它在垫子上睡得正香。", "It's fast asleep on its mat."),
    ("c_l4_woke", "它被吓醒了，一下抬起头，耳朵往后贴。", "It woke with a start, head up, ears pinned back."),
    ("c_l4_sleep", "嘘，让它睡吧。", "Shh. Let it sleep."),
    ("c_mom", "狗妈妈带着小崽在那边。", "A mom and her pup are over there."),
    ("c_l5_wait", "远远看就好，别盯着它们，也别往前凑。", "Watch from here. Don't stare, don't lean in."),
    ("c_l5_wary", "狗妈妈挡到小崽前面了。侧个身，看看别处。", "Mom stepped in front of her pup. Turn a bit, look somewhere else."),
    ("c_l5_close", "狗妈妈一下挡到小崽前面，耳朵往后贴，盯着你。", "Mom jumped in front of her pup, ears back, staring at you."),
    ("c_fence", "栅栏后面有只大狗看着你。", "A big dog's watching you from behind the fence."),
    ("c_l6_reach", "它冲到栅栏边，定住了，耳朵往后贴。", "It rushed to the fence and froze, ears back."),
    ("c_l6_wait", "好，就站这儿，等主人来。", "Good. Stay put and wait for the owner."),
    ("c_back_hint", "需要的话点 回到面前", "Tap Bring to front if you need to."),
    ("c_free", "这只狗%1$s。用你学到的招数试试。", "This one's %1$s. Try what you've learned."),
    # results
    ("r_ok", "答对啦", "Nailed it"),
    ("r_bad", "不太对", "Not quite"),
    ("r_left", "它溜了", "It took off"),
    ("r_l1_ok", "漂亮！它闻完你，心满意足地走了。跟陌生狗打交道就这么简单：不盯、不凑、不伸手。", "Nice! It sniffed you and trotted off happy. That's all it takes with a strange dog: no staring, no leaning, no reaching."),
    ("r_l1_left", "它溜了。没事，再来一次：别盯，侧个身，站稳。", "It took off. No big deal, go again: don't stare, turn a bit sideways, stand still."),
    ("r_l2_ok", "先问主人，主人最懂自己的狗。它闻完你，也就放心了。", "Ask the owner first. They know their dog best. Once it's sniffed you, it can relax."),
    ("r_l2_rush", "直接冲上去，它会被吓到。下次先问一句：能摸吗？", "Rushing in scares it. Next time just ask: can I pet it?"),
    ("r_l2_run", "在狗旁边跑，它会想追你。站稳，先问主人。", "Run near a dog and it wants to chase. Stand still and ask the owner."),
    ("r_l3_ok", "狗吃饭的时候会护着自己的碗。别盯它，也别过去，等它吃完，它更愿意认识你。", "Dogs can get protective of their bowl. Don't stare, don't walk over. Wait till it's done and it'll be happier to meet you."),
    ("r_l3_pat", "它吃饭时你凑过去，它会护着碗。等它吃完再说。", "Walk up while it's eating and it guards the bowl. Wait till it's done."),
    ("r_l3_stare", "它被你盯得吃不下了。下次侧个身，别看它的碗。", "You stared it off its dinner. Next time turn sideways and don't watch the bowl."),
    ("r_l4_ok", "睡着的狗被吵醒容易吓一跳。让它睡，醒了再说。", "A dog woken up suddenly can get a fright. Let it sleep, say hi later."),
    ("r_l4_bad", "被吓醒的狗可能会紧张。让它接着睡就好。", "A dog that wakes with a fright can get nervous. Just let it sleep."),
    ("r_l5_ok", "带崽的狗妈妈特别护崽。远远看着，大家都自在。", "Mom dogs are very protective. Watch from a distance and everyone's happy."),
    ("r_l5_bad", "凑近小崽，狗妈妈会紧张。远远看就好。", "Get close to the pup and mom gets nervous. Watch from a distance."),
    ("r_l6_ok", "狗会护着自己的地盘。别隔着栅栏伸手，等主人来。", "Dogs can guard their turf. Don't reach over a fence. Wait for the owner."),
    ("r_l6_bad", "隔着栅栏伸手，它会觉得你在闯它的地盘。", "Reach over a fence and it thinks you're barging onto its turf."),
    ("r_source", "这条规矩来自 AVMA（美国兽医协会）· Preventing dog bites", "Where this rule comes from: AVMA · Preventing dog bites"),
    ("r_source_l1", "这条规矩来自 AVMA（美国兽医协会）· Preventing dog bites；「侧过身」转述自 AVMA", "Where this rule comes from: AVMA · Preventing dog bites (\"turn sideways\" paraphrases AVMA)"),
    ("next", "下一个", "Next one"),
    ("again", "再玩一次", "Play again"),
    ("g_no_stare", "没盯着看", "You didn't stare"),
    ("g_side", "侧过身", "You turned sideways"),
    ("g_still", "它闻你时没乱动", "You held still while it sniffed"),
    ("g_no_lean", "没往前凑", "You didn't lean in"),
    ("g_asked", "先问了主人", "You asked the owner first"),
    ("g_waited", "侧着身耐心等", "You waited side-on, nice and patient"),
    ("g_choice", "选对了做法", "You picked the right move"),
    ("goals_label", "这一场：", "This one:"),
    # S15 if scared
    ("scared_title", "要是你有点怕", "If you feel threatened"),
    ("scared", "别慌，别喊，别跑。别看它的眼睛，站着别动，或者慢慢往后退。", "Stay calm. Don't yell, don't run. Avoid eye contact. Stand still or back away slowly."),
    # lesson buttons
    ("restart", "重来", "Start over"),
    ("skip_intro", "跳过讲解", "Skip intro"),
    ("back", "返回", "Back"),
    # P2
    ("tag_wary", "有点紧张", "A bit nervous"),
    ("tag_relaxed", "放松", "Chill"),
    ("tag_play", "想玩", "Wants to play"),
    ("sig_ears", "耳朵", "Ears"),
    ("sig_tail", "尾巴", "Tail"),
    ("sig_mouth", "嘴", "Mouth"),
    ("wary_ears", "往后贴", "flat back"),
    ("wary_tail", "垂着不摇", "low, no wag"),
    ("wary_mouth", "抿得紧紧的", "shut tight"),
    ("relaxed_ears", "自然竖着", "up, easy"),
    ("relaxed_tail", "慢悠悠地摇", "slow lazy wag"),
    ("relaxed_mouth", "微微张着", "a little open"),
    ("play_ears", "朝前", "forward"),
    ("play_tail", "翘高快摇", "up high, fast wag"),
    ("play_mouth", "咧开", "big grin"),
    # S10 guide
    ("guide_note", "它在原地换给你看。看耳朵、尾巴和嘴。", "It switches moods right here. Watch the ears, tail and mouth."),
    ("early_title", "早期紧张信号", "Early signs it's uneasy"),
    ("early_yawn", "打哈欠", "Yawning"),
    ("early_lick", "舔嘴唇", "Lip licking"),
    ("early_whale", "露眼白", "Whites of the eyes"),
    ("early_paw", "抬一只爪", "One paw up"),
    ("early_note", "看到这几个小动作，说明它有点不自在，给它点空间。", "Spot these and it's feeling uneasy. Give it some space."),
    ("early_source", "出处：AVMA、AKC", "Sources: AVMA, AKC"),
    # S11 free
    ("free_shy", "胆小", "shy"),
    ("free_normal", "普通", "easygoing"),
    ("free_outgoing", "外向", "outgoing"),
    ("free_another", "换一只", "Another dog"),
    ("free_mood", "性格：%1$s", "Personality: %1$s"),
    # S12 settings
    ("stand", "站着", "Standing"),
    ("sit", "坐着", "Seated"),
    ("tag_on", "信号牌 开", "Signal tag on"),
    ("tag_off", "信号牌 关", "Signal tag off"),
    ("reset_progress", "重置进度", "Reset progress"),
    ("reset_done", "进度清空了", "Progress cleared"),
    ("about", "关于：狗模型「Animated Dog, Shiba Inu」by quander，CC BY 4.0（毛色是我们改的）。行为建议出处：AVMA、AKC、ASPCA。",
     "About: dog model \"Animated Dog, Shiba Inu\" by quander, CC BY 4.0 (coats recoloured by us). Sources: AVMA, AKC, ASPCA."),
    ("about_links", "avma.org/resources-tools/pet-owners/dog-bite-prevention · akc.org/expert-advice/training/how-to-read-dog-body-language · aspca.org/pet-care/dog-care/common-dog-behavior-issues/aggression",
     "avma.org/resources-tools/pet-owners/dog-bite-prevention · akc.org/expert-advice/training/how-to-read-dog-body-language · aspca.org/pet-care/dog-care/common-dog-behavior-issues/aggression"),
    ("version", "版本 %1$s", "Version %1$s"),
    ("quit", "退出应用", "Quit app"),
    ("quit_confirm", "退出别盯着狗？", "Quit Dog Manners?"),
    ("cancel", "取消", "Cancel"),
    ("quit_yes", "退出", "Quit"),
    ("lesson_n", "小场面 %1$d", "Scene %1$d"),
    # D27 near misses (not in the design package: written to content.tone)
    ("near_face", "差一点！脸再往左转一点就行。", "So close! Turn your face a little more to the left."),
    ("near_lean", "差一点！身子往后收一点。", "So close! Lean back a little."),
    ("near_loosened", "这回放宽一点，再试试。", "Made it a bit easier. Go again."),
]


def xml(lang):
    out = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- generated by tools/strings_table.py: edit the table, not this file -->", "<resources>"]
    for key, zh, en in S:
        v = zh if lang == "zh" else en
        v = escape(v).replace("'", "\\'").replace('"', '\\"')
        if key in ("app_name",) and lang == "zh":
            pass
        out.append('    <string name="%s"%s>%s</string>' % (key, ' translatable="false"' if key in ("lang_zh", "lang_en") and lang == "en" else "", v))
    out.append("</resources>")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    res = os.path.join(ROOT, "app", "src", "main", "res")
    open(os.path.join(res, "values", "strings.xml"), "w", encoding="utf-8").write(xml("en"))
    os.makedirs(os.path.join(res, "values-zh"), exist_ok=True)
    open(os.path.join(res, "values-zh", "strings.xml"), "w", encoding="utf-8").write(xml("zh"))
    chars = sorted(set("".join(zh for _, zh, _ in S)))
    open(os.path.join(ROOT, "tools", "zh_chars.txt"), "w", encoding="utf-8").write("".join(chars))
    print("strings", len(S), "zh chars", len(chars))
