const _ = require("lodash");

const items = [3, 1, 4, 1, 5, 9, 2, 6];
console.log("Hello from MegaRepo npm test!");
console.log("Sorted:", _.sortBy(items));
console.log("Unique:", _.uniq(items));
console.log("Chunk:", _.chunk(items, 3));
