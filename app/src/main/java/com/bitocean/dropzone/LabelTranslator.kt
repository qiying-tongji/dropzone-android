package com.bitocean.dropzone

/** Local, deterministic translations for ML Kit's generic English labels. */
object LabelTranslator {
 private val zh = mapOf(
  "animal" to "动物", "baby" to "婴儿", "beach" to "海滩", "beauty" to "美貌",
  "bicycle" to "自行车", "bird" to "鸟", "black" to "黑色", "blue" to "蓝色",
  "boat" to "船", "boy" to "男孩", "bread" to "面包", "bridge" to "桥梁",
  "branch" to "树枝", "bride" to "新娘", "building" to "建筑", "butterfly" to "蝴蝶", "car" to "汽车", "cat" to "猫",
  "cathedral" to "大教堂", "chair" to "椅子", "child" to "儿童", "church" to "教堂",
  "city" to "城市", "clothing" to "服装", "cloud" to "云", "coffee" to "咖啡",
  "computer" to "电脑", "cookie" to "饼干", "cup" to "杯子", "day" to "白天",
  "cave" to "洞穴", "cuisine" to "菜肴", "desk" to "书桌", "dessert" to "甜点", "dish" to "菜肴", "dog" to "狗",
  "dress" to "连衣裙", "drink" to "饮品", "ear" to "耳朵", "event" to "活动", "face" to "人脸",
  "fashion" to "时尚", "field" to "田野", "fish" to "鱼", "flower" to "花",
  "food" to "食物", "forest" to "森林", "fun" to "娱乐", "furniture" to "家具",
  "girl" to "女孩", "gown" to "礼服", "grass" to "草地", "green" to "绿色",
  "flesh" to "皮肤", "hair" to "头发", "hand" to "手", "hat" to "帽子", "helmet" to "头盔", "house" to "房屋", "human" to "人物",
  "ingredient" to "食材", "insect" to "昆虫", "jacket" to "夹克", "jeans" to "牛仔裤",
  "jewellery" to "珠宝", "jewelry" to "珠宝", "lake" to "湖泊", "landmark" to "地标",
  "landscape" to "风景", "leisure" to "休闲", "lightning" to "闪电", "man" to "男性",
  "marriage" to "婚礼", "metal" to "金属", "model" to "模特", "monochrome" to "黑白", "mountain" to "山", "mouth" to "嘴",
  "musical instrument" to "乐器", "nail" to "指甲", "nature" to "自然", "night" to "夜景",
  "ocean" to "海洋", "office" to "办公室", "outerwear" to "外套", "pattern" to "图案", "people" to "人物", "petal" to "花瓣", "prairie" to "草原",
  "person" to "人物", "plant" to "植物", "portrait" to "人像", "product" to "商品",
  "red" to "红色", "ring" to "戒指", "river" to "河流", "road" to "道路", "rock" to "岩石", "room" to "室内", "saucer" to "茶碟",
  "sea" to "大海", "shirt" to "衬衫", "shoe" to "鞋", "shorts" to "短裤",
  "sitting" to "坐姿", "sky" to "天空", "skyline" to "城市天际线", "skyscraper" to "摩天大楼",
  "smile" to "微笑", "space" to "太空", "sportswear" to "运动服", "street" to "街道",
  "sunset" to "日落", "swimwear" to "泳装", "table" to "桌子", "tableware" to "餐具",
  "soil" to "土壤", "tower" to "塔", "toy" to "玩具", "train" to "火车", "tree" to "树", "trunk" to "树干", "twig" to "细枝",
  "underwater" to "水下", "vacation" to "度假", "vegetable" to "蔬菜", "vehicle" to "交通工具", "water" to "水域",
  "white" to "白色", "wildlife" to "野生动物", "wing" to "翅膀", "woman" to "女性",
  "wine" to "葡萄酒", "alcohol" to "酒精饮品", "wood" to "木材", "yellow" to "黄色"
 )

 fun translate(label:String):String = zh[label.trim().lowercase()] ?: "其他类别"

 fun localizeLine(line:String):String = line.split(" · ").filter{it.isNotBlank()}.joinToString(" · "){part->
  val match=Regex("^(.+?)\\s+(\\d+)%$").matchEntire(part.trim())
  if(match==null) translate(part) else "${translate(match.groupValues[1])} ${match.groupValues[2]}%"
 }
}
