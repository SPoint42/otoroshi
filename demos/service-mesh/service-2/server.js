const express = require('express');
const fetch = require('node-fetch');
const app = express();
const port = process.env.PORT || 5432;

app.get('/api', (req, res) => {
  console.log('request on service-2')
  // should be localhost if on the same pod
  const start = Date.now();
  console.log('Calling service-3 from service-2 ...')
  fetch('https://otoroshi-service-2.foo.bar:8080/api', {
    method: 'GET',
    headers: {
      'Host': 'service-3.foo.bar',
      'Accept': 'application/json'
    }
  }).then(r => r.json()).then(res1 => {
    res.status(200).send({ emitter: 'service-2', other: res1, service3CallDuration: Date.now() - start });
  })
});

app.listen(port, () => {
  console.log(`service-2 listening on port ${port}!`);
});