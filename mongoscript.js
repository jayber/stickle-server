db = db.getSiblingDB("stickle");
print("users: " + db.user.count());
cursor = db.user.find({}, {_id: false, displayName: true});
while (cursor.hasNext()) {
    printjson(cursor.next());
}
print("feedback: " + db.feedback.count());
cursor = db.feedback.find({}, {_id: false, displayName: true, title: true, content: true});
while (cursor.hasNext()) {
    printjson(cursor.next());
}