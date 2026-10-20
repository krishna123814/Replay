
## 💵 Money Manager (v4.1 — Part 1)
- Start page par **💵 Money Manager** button. Server wale chart.html ka Money Manager ab native hai, data wahi: `/data/app_state/moneymgr.json`.
- Login File Manager wala hi use hota hai (pehle File Manager me ek baar login). Load = WebDAV (`/dav/app_state/moneymgr.json`), Save = `POST /api/local_state_save` (server Telegram ke changes khud merge karta hai, web app aur Telegram ke saath sync rehta hai).
- Part 1 me: Daily (month nav, Income/Expense cards -> category-wise, Balance / Actual / Difference), ➕ add / edit / delete (date-time, account, category, sub, note, Continue), ⚙ categories (add / rename / delete + sub), Monthly, Total (account-wise balance). "rich kids" ₹500 rule server wale app jaisa hi.
- Offline ho to purana cache dikhta hai (sirf dekhne ke liye). ⟳ se dobara load.
- Part 2 me aayega: Yearly, Category, Analysis charts, Plan, Budget, Search, Export/Import.
